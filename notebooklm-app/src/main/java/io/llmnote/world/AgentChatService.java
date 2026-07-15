package io.llmnote.world;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.harness.agent.HarnessAgent;
import io.llmnote.config.NotebookLmProperties;
import io.llmnote.llm.ChatModelFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 用户与某位小镇居民的 1:1 对话。用带长期记忆的 {@link HarnessAgent}(默认最便宜模型)应答,
 * 存对话消息(含 token 计费)+ 写一条 dialogue 长期记忆。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentChatService {

    private final AgentEmployeeRepository employeeRepo;
    private final AgentChatMsgRepository chatRepo;
    private final EmployeeAgentFactory agentFactory;
    private final ChatModelFactory modelFactory;
    private final AgentMemoryService memoryService;
    private final AgentMemoryRepository memoryRepo;
    private final NotebookLmProperties props;

    public List<AgentChatMsg> history(Long agentId, String ownerId) {
        return chatRepo.findByAgentIdAndOwnerIdOrderByIdAsc(agentId, ownerId);
    }

    public AgentChatMsg chat(Long agentId, String ownerId, String message) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("消息不能为空");
        }
        AgentEmployee e = employeeRepo.findById(agentId)
                .orElseThrow(() -> new IllegalArgumentException("agent not found: " + agentId));

        // 存用户消息
        AgentChatMsg userMsg = new AgentChatMsg();
        userMsg.setAgentId(agentId);
        userMsg.setOwnerId(ownerId);
        userMsg.setRole("user");
        userMsg.setContent(message.trim());
        chatRepo.save(userMsg);

        String model = props.getWorld().getAutonomousModel(); // 默认最便宜
        HarnessAgent agent = agentFactory.forEmployee(e, model);

        // 婚姻/家庭关系与高光记忆存在 DB(spouse_id / agent_memory),但 HarnessAgent 的
        // workspace 记忆与之独立;不注入的话对话时它「不记得」自己结了婚。每次带上当前快照。
        String facts = relationshipFacts(e);
        String prompt = (facts.isEmpty() ? "" : facts + "\n")
                + "有人来找你聊天,对你说:「" + message.trim() + "」。请以你的身份和性格自然回应(2~4 句)。";
        long inTok = 0, outTok = 0;
        String reply;
        try {
            Msg m = agent.call(prompt, RuntimeContext.empty()).block();
            reply = m == null || m.getTextContent() == null ? "(沉默)" : m.getTextContent().trim();
            ChatUsage u = m == null ? null : m.getChatUsage();
            if (u != null) { inTok = u.getInputTokens(); outTok = u.getOutputTokens(); }
        } catch (Exception ex) {
            log.error("agent chat failed agentId={}", agentId, ex);
            reply = "(它似乎走神了,没有回应)";
        }

        BigDecimal cost = BigDecimal.valueOf(modelFactory.costRmb(model, inTok, outTok));
        AgentChatMsg agentMsg = new AgentChatMsg();
        agentMsg.setAgentId(agentId);
        agentMsg.setOwnerId(ownerId);
        agentMsg.setRole("agent");
        agentMsg.setContent(reply);
        agentMsg.setInputTokens(inTok);
        agentMsg.setOutputTokens(outTok);
        agentMsg.setCostRmb(cost);
        AgentChatMsg saved = chatRepo.save(agentMsg);

        // 写长期记忆(可观测事件流)
        memoryService.record(agentId, "dialogue",
                "有人对我说「" + message.trim() + "」,我回应:" + reply, 5, null);

        return saved;
    }

    /**
     * 拼出该居民当前的关系状态 + 几条高光记忆,作为对话前置事实注入,避免它「忘记」自己结了婚/有孩子。
     * 数据取自 DB(spouse_id/partner_id/parent_ids + agent_memory 高权重记录),每次实时读取,故不受
     * agent 缓存签名影响。
     */
    private String relationshipFacts(AgentEmployee e) {
        StringBuilder sb = new StringBuilder();

        if (e.getSpouseId() != null) {
            nameOf(e.getSpouseId()).ifPresent(n -> sb.append("你已经和「").append(n).append("」结婚了。"));
        } else if (e.getPartnerId() != null) {
            nameOf(e.getPartnerId()).ifPresent(n -> sb.append("你正在和「").append(n).append("」恋爱。"));
        }
        if (e.getParentIds() != null && !e.getParentIds().isBlank()) {
            List<String> parents = new ArrayList<>();
            for (String pid : e.getParentIds().split(",")) {
                if (pid.isBlank()) continue;
                try {
                    nameOf(Long.parseLong(pid.trim())).ifPresent(parents::add);
                } catch (NumberFormatException ignore) {
                }
            }
            if (!parents.isEmpty()) {
                sb.append("你的父母是").append(String.join("、", parents)).append("。");
            }
        }
        // 谁把它当作父母(即它的孩子)。
        List<AgentEmployee> children = employeeRepo.findByParentIdsContaining(String.valueOf(e.getId()));
        List<String> kids = new ArrayList<>();
        for (AgentEmployee c : children) {
            if (c.getParentIds() == null) continue;
            for (String pid : c.getParentIds().split(",")) {
                if (pid.trim().equals(String.valueOf(e.getId()))) {
                    kids.add(c.getName());
                    break;
                }
            }
        }
        if (!kids.isEmpty()) {
            sb.append("你有孩子:").append(String.join("、", kids)).append("。");
        }

        // 高光记忆(结婚/生子/重大事件等),取重要度最高的前几条。
        List<AgentMemory> highlights = memoryRepo.findTop12ByAgentIdOrderByImportanceDescIdDesc(e.getId());
        List<String> lines = new ArrayList<>();
        for (AgentMemory m : highlights) {
            if (m.getImportance() != null && m.getImportance() >= 7
                    && m.getContent() != null && !m.getContent().isBlank()) {
                String c = m.getContent().trim();
                if (c.length() > 60) c = c.substring(0, 60);
                lines.add(c);
                if (lines.size() >= 5) break;
            }
        }
        if (!lines.isEmpty()) {
            sb.append("你人生中的重要记忆:").append(String.join(";", lines)).append("。");
        }

        return sb.length() == 0 ? "" : "【关于你自己的事实,请当作真实经历】" + sb;
    }

    private Optional<String> nameOf(Long id) {
        if (id == null) return Optional.empty();
        return employeeRepo.findById(id).map(AgentEmployee::getName);
    }
}
