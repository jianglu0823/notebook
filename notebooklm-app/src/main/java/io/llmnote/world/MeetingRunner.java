package io.llmnote.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.harness.agent.HarnessAgent;
import io.llmnote.llm.ChatCompletion;
import io.llmnote.llm.ChatModelFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 会议编排的异步执行体。独立于 {@link MeetingService} 是为了让 {@code @Async} 生效
 * —— Spring 的 @Async 走代理,若在同一个 bean 内自调用会失效(退化成同步),
 * 导致「发起会议」的 HTTP 请求阻塞到整场会议跑完。拆成独立 bean 由 service 注入调用即可修复。
 *
 * 每名参会员工用 {@link HarnessAgent}(带长期记忆的 agent,本场选定模型)发言;轮流发言,
 * transcript 累积供后来者看到,每句实时 emit 进 events 时间线;并累计 token,按模型单价换算费用。
 * 会议结束把纪要写进每位参会员工的长期记忆(agent_memory)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MeetingRunner {

    private final ObjectMapper objectMapper;
    private final ChatCompletion chatCompletion;
    private final ChatModelFactory modelFactory;
    private final EmployeeAgentFactory agentFactory;
    private final AgentMeetingRepository repo;
    private final AgentEmployeeRepository employeeRepo;
    private final AgentMemoryService memoryService;

    @Async
    public void run(Long id) {
        AgentMeeting m = repo.findById(id).orElse(null);
        if (m == null) return;
        long[] tok = {0L, 0L}; // [inputTokens, outputTokens]
        try {
            m.setStatus("RUNNING");
            repo.save(m);

            String modelName = modelFactory.normalize(m.getModel());
            DashScopeChatModel model = modelFactory.forModel(modelName);

            List<Long> ids = objectMapper.readValue(m.getParticipantIds(),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, Long.class));
            List<AgentEmployee> members = loadMembers(ids);

            ArrayNode eventsArr = objectMapper.createArrayNode();
            emit(m, eventsArr, "start", "system", "system", "#8a9ba3",
                    "会议开始:议题「" + m.getTopic() + "」," + members.size() + " 人参会,共 "
                            + m.getMaxRounds() + " 轮发言。模型:" + modelName);

            List<HarnessAgent> agents = new ArrayList<>();
            for (AgentEmployee e : members) {
                agents.add(agentFactory.forEmployee(e, modelName));
            }

            StringBuilder transcript = new StringBuilder();
            for (int round = 1; round <= m.getMaxRounds(); round++) {
                emit(m, eventsArr, "round", "system", "system", "#8a9ba3", "—— 第 " + round + " 轮 ——");
                long roundIn = tok[0], roundOut = tok[1];
                for (int i = 0; i < members.size(); i++) {
                    AgentEmployee e = members.get(i);
                    String prompt = buildTurnPrompt(m.getTopic(), e, transcript.toString(), round);
                    String say = ask(agents.get(i), prompt, tok);
                    if (say.isBlank()) say = "(沉默)";
                    transcript.append(e.getName()).append(":").append(say).append("\n\n");
                    emit(m, eventsArr, "speak", e.getName(), String.valueOf(e.getId()),
                            e.getColor() == null ? "#2dd4bf" : e.getColor(),
                            say, e.getAvatar(), e.getTitle());
                    log.info("meeting {} round {} speaker {} chars={}", id, round, e.getName(), say.length());
                }
                long dIn = tok[0] - roundIn, dOut = tok[1] - roundOut;
                emit(m, eventsArr, "usage", "system", "system", "#8a9ba3",
                        "第 " + round + " 轮 token:输入 " + dIn + " / 输出 " + dOut
                                + "(累计 " + tok[0] + "/" + tok[1] + ")");
            }

            // 主持人总结(计入 token)
            emit(m, eventsArr, "think", "主持人", "system", "#8a9ba3", "🧑‍⚖️ 主持人正在总结……");
            ChatCompletion.Result sum = chatCompletion.completeWithUsage(
                    "你是一场圆桌讨论的主持人。请基于完整发言记录,提炼这场关于「" + m.getTopic()
                            + "」的讨论的核心观点、共识与分歧,简洁地给出一段总结(200 字内)。",
                    transcript.toString(), model);
            tok[0] += sum.inputTokens();
            tok[1] += sum.outputTokens();
            String summary = sum.text() == null ? "" : sum.text().trim();
            emit(m, eventsArr, "summary", "主持人", "system", "#8a9ba3", summary);

            BigDecimal cost = BigDecimal.valueOf(modelFactory.costRmb(modelName, tok[0], tok[1]));
            m.setSummary(summary);
            m.setModel(modelName);
            m.setInputTokens(tok[0]);
            m.setOutputTokens(tok[1]);
            m.setCostRmb(cost);
            m.setStatus("DONE");
            repo.save(m);
            log.info("meeting done id={} model={} tokens={}/{} cost={}", id, modelName, tok[0], tok[1], cost);

            // 把会议纪要写进每位参会居民的长期记忆
            String memo = "我参加了关于「" + m.getTopic() + "」的会议。会议总结:" + summary;
            for (AgentEmployee e : members) {
                memoryService.record(e.getId(), "meeting", memo, 6, null);
            }
        } catch (Exception ex) {
            log.error("meeting failed id={}", id, ex);
            fail(m, ex.getMessage());
        }
    }

    private String buildTurnPrompt(String topic, AgentEmployee e, String transcript, int round) {
        if (transcript.isBlank()) {
            return "会议开始,议题:「" + topic + "」。请你作为「" + e.getName() + "」率先发言,给出你的观点。";
        }
        return "议题:「" + topic + "」。以下是目前为止的发言记录:\n\n" + transcript
                + "\n现在轮到你(" + e.getName() + ")发言。请结合上面的讨论,表达你的观点或回应。";
    }

    private void emit(AgentMeeting m, ArrayNode eventsArr, String type, String role, String speakerId,
                      String color, String text, String... detail) {
        try {
            ObjectNode ev = objectMapper.createObjectNode();
            ev.put("t", System.currentTimeMillis());
            ev.put("type", type);
            ev.put("role", role);
            ev.put("speakerId", speakerId);
            ev.put("color", color);
            ev.put("text", text);
            if (detail.length > 0 && detail[0] != null) ev.put("avatar", detail[0]);
            if (detail.length > 1 && detail[1] != null) ev.put("title", detail[1]);
            eventsArr.add(ev);
            m.setEvents(objectMapper.writeValueAsString(eventsArr));
            repo.save(m);
        } catch (Exception ignore) { /* 事件写入失败不影响主流程 */ }
    }

    /** 调用一名员工发言,并把本次 token 用量累加进 tok[]。 */
    private String ask(HarnessAgent agent, String prompt, long[] tok) {
        Msg reply = agent.call(prompt, RuntimeContext.empty()).block();
        if (reply == null) return "";
        ChatUsage u = reply.getChatUsage();
        if (u != null) { tok[0] += u.getInputTokens(); tok[1] += u.getOutputTokens(); }
        String text = reply.getTextContent();
        return text == null ? "" : text.trim();
    }

    private List<AgentEmployee> loadMembers(List<Long> ids) {
        List<AgentEmployee> out = new ArrayList<>();
        for (Long eid : ids) {
            employeeRepo.findById(eid).ifPresent(out::add);
        }
        return out;
    }

    private void fail(AgentMeeting m, String msg) {
        if (m == null) return;
        m.setStatus("FAILED");
        m.setErrorMsg(msg != null && msg.length() > 2000 ? msg.substring(0, 2000) : msg);
        repo.save(m);
    }
}
