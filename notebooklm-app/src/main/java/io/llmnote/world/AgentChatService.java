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
import java.util.List;

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

        String prompt = "有人来找你聊天,对你说:「" + message.trim() + "」。请以你的身份和性格自然回应(2~4 句)。";
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
}
