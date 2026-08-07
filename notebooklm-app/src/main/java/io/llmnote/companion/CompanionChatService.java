package io.llmnote.companion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.llmnote.auth.ForbiddenException;
import io.llmnote.companion.config.CompanionProperties;
import io.llmnote.companion.enums.MessageRole;
import io.llmnote.llm.ChatModelFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 伴侣聊天核心服务:用户消息入库 → 构造上下文 → 调 LLM 流式 → 助手消息入库。
 * - 上下文 = system prompt + 长期记忆块 + 近期历史(最多 20 条 / 4000 字)。
 * - 模型优先级:chat.text 帧 model 字段 > 伴侣 defaultModel > 全局默认;过 ChatModelFactory.normalize()。
 * - B3 阶段:长期记忆块先传空 list(B4 接入 Milvus 后实装);历史窗口暂不过滤(B3 简化,后续可加)。
 */
@Slf4j
@Service
public class CompanionChatService {

    private final CompanionRepository companionRepository;
    private final CompanionMessageRepository messageRepository;
    private final CompanionMemoryService memoryService;
    private final CompanionIntimacyService intimacyService;
    private final ChatModelFactory modelFactory;
    private final CompanionVisionService visionService;
    private final CompanionProperties properties;
    private final ObjectMapper objectMapper;

    public CompanionChatService(CompanionRepository companionRepository,
                                CompanionMessageRepository messageRepository,
                                CompanionMemoryService memoryService,
                                CompanionIntimacyService intimacyService,
                                ChatModelFactory modelFactory,
                                CompanionVisionService visionService,
                                CompanionProperties properties,
                                ObjectMapper objectMapper) {
        this.companionRepository = companionRepository;
        this.messageRepository = messageRepository;
        this.memoryService = memoryService;
        this.intimacyService = intimacyService;
        this.modelFactory = modelFactory;
        this.visionService = visionService;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public interface DeltaSink {
        void onDelta(String delta);
        void onDone(String fullText, String model, Long inputTokens, Long outputTokens, String requestId);
        void onError(String error);
        /** 亲密度里程碑(可空);7/30/100 天触发时由本服务上抛,默认 no-op。 */
        default void onMilestones(java.util.List<io.llmnote.companion.dto.Milestone> milestones) {}
    }

    /**
     * 处理一条用户消息(纯文字或带图附件);流式推 delta,完整消息入库。
     * @return 助手消息 id(供后续 delete/regenerate 引用);失败返回 null。
     */
    @Transactional
    public Long handleUserMessage(String userId, Long companionId, String content,
                                  String modelOverride, JsonNode attachments, DeltaSink sink) {
        Companion c = requireOwned(userId, companionId);
        if (content == null || content.isBlank()) {
            sink.onError("content 不能为空");
            return null;
        }

        // 1. 落库 user 消息
        CompanionMessage userMsg = newMessage(companionId, MessageRole.USER, content, attachments, null);
        messageRepository.save(userMsg);

        // 1.5 记录亲密度(每条 user 消息 +1,日上限),命中里程碑就推一次
        List<io.llmnote.companion.dto.Milestone> milestones = List.of();
        try {
            milestones = intimacyService.recordInteraction(userId, companionId).milestones();
        } catch (Exception e) {
            log.warn("intimacy record failed: {}", e.getMessage());
        }
        if (!milestones.isEmpty()) sink.onMilestones(milestones);

        // 2. 选模型:有图附件 → 视觉模型(按伴侣 defaultModel 派系,C8);否则按 chat.text 帧 model > 伴侣 defaultModel
        boolean hasImage = hasImageAttachment(attachments);
        String model;
        if (hasImage) {
            model = visionService.pickVisionModel(c.getDefaultModel());
        } else {
            model = modelFactory.normalize(
                    modelOverride != null && !modelOverride.isBlank() ? modelOverride : c.getDefaultModel());
        }

        // 3. 构造 LLM 上下文(检索长期记忆注入 system prompt)
        List<Msg> ctx = buildContext(c, userId, content, attachments);
        if (ctx == null) {
            sink.onError("构造上下文失败");
            return null;
        }

        // 4. 流式调用 + 推 delta
        StringBuilder acc = new StringBuilder();
        long inTok = 0, outTok = 0;
        try {
            List<ChatResponse> chunks = modelFactory.streamTextWithFallback(model, ctx);
            for (ChatResponse r : chunks) {
                if (r.getContent() != null) {
                    r.getContent().forEach(b -> {
                        if (b instanceof TextBlock tb && tb.getText() != null) {
                            acc.append(tb.getText());
                            sink.onDelta(tb.getText());
                        }
                    });
                }
                ChatUsage u = r.getUsage();
                if (u != null) { inTok = u.getInputTokens(); outTok = u.getOutputTokens(); }
            }
        } catch (RuntimeException ex) {
            log.warn("LLM stream failed: {}", ex.getMessage());
            sink.onError("LLM 调用失败: " + ex.getMessage());
            return null;
        }

        String fullText = ChatModelFactory.stripThink(acc.toString());

        // 5. 落库 assistant 消息 + 更新 last_message_at
        Map<String, Object> meta = new HashMap<>();
        meta.put("model", model);
        if (inTok > 0) meta.put("inputTokens", inTok);
        if (outTok > 0) meta.put("outputTokens", outTok);
        CompanionMessage aiMsg = newMessage(companionId, MessageRole.ASSISTANT, fullText, null, meta);
        messageRepository.save(aiMsg);
        c.setLastMessageAt(LocalDateTime.now());

        sink.onDone(fullText, model, inTok, outTok, null);

        // 6. 异步抽取长期记忆(失败不阻断聊天,extractAndStoreAsync 内部已 try/catch)
        triggerMemoryExtraction(userId, companionId, aiMsg.getId(), content, fullText);

        return aiMsg.getId();
    }

    private void triggerMemoryExtraction(String userId, Long companionId, Long aiMessageId,
                                         String userText, String assistantText) {
        try {
            List<String> recentU = new ArrayList<>();
            List<String> recentA = new ArrayList<>();
            if (userText != null) recentU.add(userText);
            if (assistantText != null) recentA.add(assistantText);
            memoryService.extractAndStoreAsync(userId, companionId, aiMessageId, recentU, recentA);
        } catch (Exception e) {
            log.warn("触发记忆抽取失败: {}", e.getMessage());
        }
    }

    private List<Msg> buildContext(Companion c, String userId, String currentContent, JsonNode currentAttachments) {
        List<String> memorySnippets = memoryService.search(userId, c.getId(), currentContent);
        return buildContextInner(c, memorySnippets, currentContent, currentAttachments);
    }

    private List<Msg> buildContextInner(Companion c, List<String> memorySnippets,
                                   String currentContent, JsonNode currentAttachments) {
        List<Msg> out = new ArrayList<>();
        String system = CompanionSystemPrompt.build(c, memorySnippets);
        out.add(Msg.builder().role(MsgRole.SYSTEM).content(TextBlock.builder().text(system).build()).build());

        // 近期历史(降序遍历后逆序加入,保持时序)
        List<CompanionMessage> history = messageRepository.findVisible(c.getId(), null,
                org.springframework.data.domain.PageRequest.of(0, properties.getContextHistoryLimit()));
        java.util.Collections.reverse(history);
        int charBudget = properties.getContextHistoryWindowChars();
        int chars = 0;
        for (CompanionMessage m : history) {
            if (m.getRole() == MessageRole.USER || m.getRole() == MessageRole.ASSISTANT) {
                String t = m.getContent() == null ? "" : m.getContent();
                if (chars + t.length() > charBudget) continue;
                MsgRole role = m.getRole() == MessageRole.USER ? MsgRole.USER : MsgRole.ASSISTANT;
                out.add(Msg.builder().role(role).content(TextBlock.builder().text(t).build()).build());
                chars += t.length();
            }
        }

        // 本轮 user
        String userTurn = currentContent == null ? "" : currentContent;
        if (currentAttachments != null && currentAttachments.isArray() && !currentAttachments.isEmpty()) {
            userTurn = userTurn + "\n[附件:" + currentAttachments.toString() + "]";
        }
        out.add(Msg.builder().role(MsgRole.USER).content(TextBlock.builder().text(userTurn).build()).build());
        return out;
    }

    private CompanionMessage newMessage(Long companionId, MessageRole role, String content,
                                        JsonNode attachments, Map<String, Object> meta) {
        CompanionMessage m = new CompanionMessage();
        m.setCompanionId(companionId);
        m.setRole(role);
        m.setContent(content == null ? "" : content);
        try {
            m.setAttachments(attachments == null ? "[]" : objectMapper.writeValueAsString(attachments));
        } catch (Exception e) {
            m.setAttachments("[]");
        }
        try {
            m.setMetadata(meta == null ? "{}" : objectMapper.writeValueAsString(meta));
        } catch (Exception e) {
            m.setMetadata("{}");
        }
        return m;
    }

    private Companion requireOwned(String userId, Long id) {
        return companionRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ForbiddenException("companion not found: " + id));
    }

    private boolean hasImageAttachment(JsonNode attachments) {
        if (attachments == null || !attachments.isArray()) return false;
        for (JsonNode att : attachments) {
            String kind = att.path("kind").asText("");
            if ("image".equalsIgnoreCase(kind)) return true;
        }
        return false;
    }
}
