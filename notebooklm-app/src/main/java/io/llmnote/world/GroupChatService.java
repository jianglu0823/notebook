package io.llmnote.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.harness.agent.HarnessAgent;
import io.llmnote.llm.ChatModelFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 多智能体<b>群聊</b>:用户和若干居民同处一个会话。区别于 {@link MeetingService}(一次性自动跑完的话题会议),
 * 群聊是交互式的——用户每发一句,当前在场的每位成员<b>依次</b>回应(能看到本轮之前成员的发言),
 * 支持中途 {@link #addMember}/{@link #removeMember} 增减成员。消息与成员均持久化,可回看历史。
 *
 * <p>为省 token,默认用最便宜模型;每位成员回应 2~3 句。成员发言同时写入其长期记忆,让群聊在小镇里留痕。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GroupChatService {

    private final ObjectMapper objectMapper;
    private final AgentGroupChatRepository chatRepo;
    private final AgentGroupMsgRepository msgRepo;
    private final AgentEmployeeRepository employeeRepo;
    private final EmployeeAgentFactory agentFactory;
    private final ChatModelFactory modelFactory;
    private final AgentMemoryService memoryService;

    public AgentGroupChat create(String ownerId, String title, List<Long> memberIds, String model) {
        if (memberIds == null || memberIds.isEmpty()) {
            throw new IllegalArgumentException("请至少选择 1 名居民加入群聊");
        }
        List<AgentEmployee> members = loadMembers(memberIds);
        if (members.isEmpty()) throw new IllegalArgumentException("没有有效的居民");

        AgentGroupChat c = new AgentGroupChat();
        c.setOwnerId(ownerId);
        c.setTitle(title == null || title.isBlank()
                ? members.stream().map(AgentEmployee::getName).limit(3).reduce((a, b) -> a + "、" + b).orElse("群聊") + " 的群聊"
                : title.trim());
        c.setModel(modelFactory.normalize(model));
        c.setMemberIds(writeIds(members.stream().map(AgentEmployee::getId).toList()));
        c = chatRepo.save(c);
        sys(c.getId(), "群聊创建:" + members.stream().map(AgentEmployee::getName)
                .reduce((a, b) -> a + "、" + b).orElse("") + " 加入了群聊。");
        return c;
    }

    public List<AgentGroupChat> list(String ownerId) {
        return chatRepo.findByOwnerIdOrderByIdDesc(ownerId);
    }

    public AgentGroupChat get(Long id, String ownerId) {
        return owned(id, ownerId);
    }

    public List<AgentGroupMsg> history(Long id, String ownerId) {
        owned(id, ownerId);
        return msgRepo.findByChatIdOrderByIdAsc(id);
    }

    @Transactional
    public void delete(Long id, String ownerId) {
        owned(id, ownerId);
        msgRepo.deleteByChatId(id);
        chatRepo.deleteById(id);
    }

    /** 用户发一句,当前在场成员依次回应;返回本轮全部新消息(含用户消息)。 */
    public List<AgentGroupMsg> post(Long id, String ownerId, String message) {
        if (message == null || message.isBlank()) throw new IllegalArgumentException("消息不能为空");
        AgentGroupChat c = owned(id, ownerId);
        List<Long> ids = readIds(c.getMemberIds());
        List<AgentEmployee> members = loadMembers(ids);
        if (members.isEmpty()) throw new IllegalArgumentException("群里还没有居民,先添加成员");

        String model = modelFactory.normalize(c.getModel());
        List<AgentGroupMsg> fresh = new ArrayList<>();

        // 用户消息落库
        AgentGroupMsg um = new AgentGroupMsg();
        um.setChatId(id); um.setRole("user"); um.setContent(message.trim());
        fresh.add(msgRepo.save(um));

        // 已有对话作为上下文(取最近若干条,省 token)
        StringBuilder transcript = new StringBuilder();
        List<AgentGroupMsg> hist = msgRepo.findByChatIdOrderByIdAsc(id);
        for (AgentGroupMsg h : tail(hist, 24)) transcript.append(speaker(h, members)).append(":").append(h.getContent()).append("\n");

        long[] tok = {0L, 0L};
        for (AgentEmployee e : members) {
            String prompt = buildPrompt(e, members, message.trim(), transcript.toString());
            String say = ask(agentFactory.forEmployee(e, model), prompt, tok);
            if (say.isBlank()) say = "(沉默)";
            BigDecimal cost = BigDecimal.valueOf(modelFactory.costRmb(model, tok[0], tok[1]));
            AgentGroupMsg am = new AgentGroupMsg();
            am.setChatId(id); am.setAgentId(e.getId()); am.setRole("agent"); am.setContent(say);
            am.setInputTokens(tok[0]); am.setOutputTokens(tok[1]); am.setCostRmb(cost);
            fresh.add(msgRepo.save(am));
            transcript.append(e.getName()).append(":").append(say).append("\n");
            memoryService.record(e.getId(), "dialogue",
                    "在群聊里,有人说「" + message.trim() + "」,我回应:" + say, 4, null);
        }

        // 累计计费写回会话
        c.setInputTokens(c.getInputTokens() + tok[0]);
        c.setOutputTokens(c.getOutputTokens() + tok[1]);
        c.setCostRmb(c.getCostRmb().add(BigDecimal.valueOf(modelFactory.costRmb(model, tok[0], tok[1]))));
        chatRepo.save(c);
        return fresh;
    }

    /** 中途加人。 */
    public AgentGroupChat addMember(Long id, String ownerId, Long agentId) {
        AgentGroupChat c = owned(id, ownerId);
        List<Long> ids = readIds(c.getMemberIds());
        if (!ids.contains(agentId)) {
            AgentEmployee e = employeeRepo.findById(agentId)
                    .orElseThrow(() -> new IllegalArgumentException("居民不存在:" + agentId));
            ids.add(agentId);
            c.setMemberIds(writeIds(ids));
            chatRepo.save(c);
            sys(id, e.getName() + " 加入了群聊。");
        }
        return c;
    }

    /** 中途移除。 */
    public AgentGroupChat removeMember(Long id, String ownerId, Long agentId) {
        AgentGroupChat c = owned(id, ownerId);
        List<Long> ids = readIds(c.getMemberIds());
        if (ids.remove(agentId)) {
            c.setMemberIds(writeIds(ids));
            chatRepo.save(c);
            employeeRepo.findById(agentId).ifPresent(e -> sys(id, e.getName() + " 离开了群聊。"));
        }
        return c;
    }

    // ---- helpers ----

    private String buildPrompt(AgentEmployee me, List<AgentEmployee> members, String userMsg, String transcript) {
        String others = members.stream().filter(x -> !x.getId().equals(me.getId()))
                .map(AgentEmployee::getName).reduce((a, b) -> a + "、" + b).orElse("(暂无其他人)");
        return "这是一个群聊,群里有你和这些居民:" + others + "。\n"
                + (transcript.isBlank() ? "" : "最近的聊天记录:\n" + transcript + "\n")
                + "用户刚刚在群里说:「" + userMsg + "」。\n"
                + "请以「" + me.getName() + "」的身份和性格,自然地在群里回应(2~3 句,像真人聊天,可以回应用户或其他居民的话,不要复述别人说过的)。";
    }

    private void sys(Long chatId, String text) {
        AgentGroupMsg m = new AgentGroupMsg();
        m.setChatId(chatId); m.setRole("system"); m.setContent(text);
        msgRepo.save(m);
    }

    private String speaker(AgentGroupMsg m, List<AgentEmployee> members) {
        if ("user".equals(m.getRole())) return "用户";
        if ("system".equals(m.getRole())) return "系统";
        if (m.getAgentId() != null) {
            for (AgentEmployee e : members) if (e.getId().equals(m.getAgentId())) return e.getName();
        }
        return "居民";
    }

    private String ask(HarnessAgent agent, String prompt, long[] tok) {
        try {
            Msg reply = agent.call(prompt, RuntimeContext.empty()).block();
            if (reply == null) return "";
            ChatUsage u = reply.getChatUsage();
            if (u != null) { tok[0] += u.getInputTokens(); tok[1] += u.getOutputTokens(); }
            String text = reply.getTextContent();
            return text == null ? "" : text.trim();
        } catch (Exception ex) {
            log.warn("group chat member reply failed", ex);
            return "(它走神了)";
        }
    }

    private List<AgentEmployee> loadMembers(List<Long> ids) {
        List<AgentEmployee> out = new ArrayList<>();
        for (Long eid : ids) employeeRepo.findById(eid).ifPresent(out::add);
        return out;
    }

    private static <T> List<T> tail(List<T> list, int n) {
        return list.size() <= n ? list : list.subList(list.size() - n, list.size());
    }

    private List<Long> readIds(String json) {
        try {
            if (json == null || json.isBlank()) return new ArrayList<>();
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(ArrayList.class, Long.class));
        } catch (Exception e) { return new ArrayList<>(); }
    }

    private String writeIds(List<Long> ids) {
        try { return objectMapper.writeValueAsString(ids); } catch (Exception e) { return "[]"; }
    }

    private AgentGroupChat owned(Long id, String ownerId) {
        return chatRepo.findById(id)
                .filter(c -> c.getOwnerId().equals(ownerId))
                .orElseThrow(() -> new IllegalArgumentException("group chat not found: " + id));
    }
}
