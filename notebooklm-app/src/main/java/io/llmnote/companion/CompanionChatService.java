package io.llmnote.companion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Toolkit;
import io.llmnote.auth.ForbiddenException;
import io.llmnote.companion.a2a.ScheduleAgentClient;
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

    /** A2A 工具调用最大轮数(防止无限循环)。 */
    private static final int MAX_TOOL_ITERS = 4;

    private final CompanionRepository companionRepository;
    private final CompanionMessageRepository messageRepository;
    private final CompanionMemoryService memoryService;
    private final CompanionIntimacyService intimacyService;
    private final ChatModelFactory modelFactory;
    private final CompanionVisionService visionService;
    private final CompanionProperties properties;
    private final ObjectMapper objectMapper;
    /** 可选:A2aAgent 未启用(本机无 Nacos 3.x)时不装配,本字段为 null,跳过工具注册 */
    private final ScheduleAgentClient scheduleAgentClient;

    public CompanionChatService(CompanionRepository companionRepository,
                                CompanionMessageRepository messageRepository,
                                CompanionMemoryService memoryService,
                                CompanionIntimacyService intimacyService,
                                ChatModelFactory modelFactory,
                                CompanionVisionService visionService,
                                CompanionProperties properties,
                                ObjectMapper objectMapper,
                                @org.springframework.beans.factory.annotation.Autowired(required = false)
                                ScheduleAgentClient scheduleAgentClient) {
        this.companionRepository = companionRepository;
        this.messageRepository = messageRepository;
        this.memoryService = memoryService;
        this.intimacyService = intimacyService;
        this.modelFactory = modelFactory;
        this.visionService = visionService;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.scheduleAgentClient = scheduleAgentClient;
        if (scheduleAgentClient == null) {
            log.warn("CompanionChatService: ScheduleAgentClient 未注入(可能 A2aAgent 未启用),日程工具不可用");
        }
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

        // 2. 解析 userId(有 numeric id 才能调工具)。提前到这里,影响模型选型。
        Long parsedUserId = parseUserIdFromOwner(userId);

        // 3. 选模型:有图附件 → 视觉模型(按伴侣 defaultModel 派系,C8);登录态(有工具)→ 强制 tools-capable(qwen-turbo,
        //    智谱 GLM 系列 function calling 不可靠,降级链落到 GLM 会编结果);其他按 chat.text 帧 model > 伴侣 defaultModel
        boolean hasImage = hasImageAttachment(attachments);
        boolean forcedToolsModel = false;
        String requestedModel = modelOverride != null && !modelOverride.isBlank() ? modelOverride : c.getDefaultModel();
        String model;
        if (hasImage) {
            model = visionService.pickVisionModel(c.getDefaultModel());
        } else if (parsedUserId != null && !modelFactory.isToolsCapable(requestedModel)) {
            model = modelFactory.toolsCapableModel();
            forcedToolsModel = true;
        } else {
            model = modelFactory.normalize(requestedModel);
        }
        log.info("chat: userId={} companionId={} requestedModel={} selectedModel={} hasImage={} forcedToolsModel={}",
                userId, companionId, requestedModel, model, hasImage, forcedToolsModel);

        // 4. 构造 LLM 上下文(检索长期记忆注入 system prompt)
        List<Msg> ctx = buildContext(c, userId, content, attachments);
        if (ctx == null) {
            sink.onError("构造上下文失败");
            return null;
        }

        // 5. 流式调用 + 推 delta(带 ReAct 工具循环)
        StringBuilder fullTextBuilder = new StringBuilder();
        long inTok = 0, outTok = 0;
        boolean a2aEnabled = (parsedUserId != null && scheduleAgentClient != null);
        List<ToolSchema> toolSchemas = List.of();
        if (a2aEnabled) {
            Toolkit toolkit = new Toolkit();
            toolkit.registerTool(scheduleAgentClient);
            toolSchemas = toolkit.getToolSchemas();
        }
        // ScheduleAgentClient.@Tool 内部读 ThreadLocal userId;ReAct 循环前 set,finally clear
        if (a2aEnabled) {
            ScheduleAgentClient.setUserId(parsedUserId);
        }
        try {
            // ReAct 循环:首次传工具 schema,看到 ToolUseBlock 就执行,回灌后再次调用
            for (int iter = 0; iter < MAX_TOOL_ITERS; iter++) {
                List<ToolSchema> schemasThisTurn = (iter == 0) ? toolSchemas : List.of();
                List<ChatResponse> chunks = a2aEnabled
                        ? modelFactory.streamTextWithToolsFallback(model, ctx, null, schemasThisTurn)
                        : modelFactory.streamTextWithFallback(model, ctx, null, schemasThisTurn);
                StringBuilder iterText = new StringBuilder();
                List<ToolUseBlock> toolUses = new ArrayList<>();
                for (ChatResponse r : chunks) {
                    if (r.getContent() != null) {
                        for (ContentBlock b : r.getContent()) {
                            if (b instanceof TextBlock tb && tb.getText() != null) {
                                iterText.append(tb.getText());
                                fullTextBuilder.append(tb.getText());
                                sink.onDelta(tb.getText());
                            } else if (b instanceof ToolUseBlock tub) {
                                toolUses.add(tub);
                            }
                        }
                    }
                    ChatUsage u = r.getUsage();
                    if (u != null) { inTok += u.getInputTokens(); outTok += u.getOutputTokens(); }
                }
                if (toolUses.isEmpty()) {
                    log.info("chat LLM iter={} responded: textChars={} toolUses=0 contentBlockTypes={}",
                            iter, iterText.length(), contentBlockTypeSummary(chunks));
                    break;
                }
                log.info("chat LLM iter={} responded: textChars={} toolUses={} names={}",
                        iter, iterText.length(), toolUses.size(),
                        toolUses.stream().map(ToolUseBlock::getName).toList());
                // 工具调用回合:把当前 iter 的 assistant Msg 推入 ctx,再 append tool result Msg
                List<ContentBlock> assistantBlocks = new ArrayList<>();
                String iterTextStr = iterText.toString();
                if (!iterTextStr.isEmpty()) {
                    assistantBlocks.add(TextBlock.builder().text(iterTextStr).build());
                }
                assistantBlocks.addAll(toolUses);
                ctx = new ArrayList<>(ctx);
                ctx.add(Msg.builder().role(MsgRole.ASSISTANT).content(assistantBlocks).build());
                List<ContentBlock> toolResultBlocks = new ArrayList<>();
                int silentCount = 0;
                for (ToolUseBlock tub : toolUses) {
                    ToolDispatchResult dr = dispatchCompanionTool(tub, content);
                    log.info("tool call: name={} inputKeys={} resultLen={} silent={}",
                            tub.getName(), tub.getInput() == null ? 0 : tub.getInput().size(),
                            dr.text().length(), dr.silent());
                    toolResultBlocks.add(ToolResultBlock.builder()
                            .id(tub.getId())
                            .name(tub.getName())
                            .output(TextBlock.builder().text(dr.text()).build())
                            .build());
                    if (dr.silent()) {
                        silentCount++;
                        continue;  // 内部错误(unknown tool / 异常)不推 UI
                    }
                    String line = "\n" + dr.text() + "\n";
                    fullTextBuilder.append(line);
                    sink.onDelta(line);
                }
                // 防御:LLM 卡死在 hallucination(全 unknown / 全异常)就 break,别再喂下一轮
                if (iter > 0 && silentCount == toolUses.size()) {
                    log.warn("chat LLM iter={} 全部工具调用都返回内部错误 ({} 个),判定 LLM 卡死,跳出 ReAct 循环",
                            iter, silentCount);
                    break;
                }
                ctx.add(Msg.builder().role(MsgRole.TOOL).content(toolResultBlocks).build());
            }
        } catch (RuntimeException ex) {
            log.warn("LLM stream failed: {}", ex.getMessage());
            sink.onError("LLM 调用失败: " + ex.getMessage());
            return null;
        } finally {
            if (a2aEnabled) {
                ScheduleAgentClient.clearUserId();
            }
        }

        String fullText = ChatModelFactory.stripThink(fullTextBuilder.toString());

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

    /** 从 ownerId 解析数字 userId。ownerId="u:123" → 123L;"g:xxx" 或 null → null(游客不调工具)。 */
    private Long parseUserIdFromOwner(String ownerId) {
        if (ownerId == null || !ownerId.startsWith("u:")) return null;
        try {
            return Long.parseLong(ownerId.substring(2));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 工具派发结果:result 是给 LLM 看的 tool-result 文本;silent=true 表示内部错误(不推 UI 增量)。 */
    private record ToolDispatchResult(String text, boolean silent) {
        static ToolDispatchResult visible(String t) { return new ToolDispatchResult(t, false); }
        static ToolDispatchResult silent(String t) { return new ToolDispatchResult(t, true); }
    }

    /**
     * 把 ToolUseBlock 派发到具体的 @Tool 方法,直接调用以避免 AgentTool.callAsync 的运行时依赖。
     * 工具不存在 / 必填参数缺失 / A2aAgent 未装配 / @Tool 抛异常 → 标记 silent=true,不推 UI 增量
     * (避免 LLM hallucination 噪声污染聊天;R-c6.3 Scenario 工具结果为 silent)。
     * <p>
     * 单一工具模式(伴偶视角只有 {@code delegate_to_schedule_agent}):任何 tool_use 都走这个分支;
     * 走 schedule-agent 微服务后,LLM 不再直接面对 12 个底层 @Tool。
     * userId 通过 ScheduleAgentClient 的 ThreadLocal 传递(本方法不显式传)。
     */
    private ToolDispatchResult dispatchCompanionTool(ToolUseBlock tub, String originalUserContent) {
        if (scheduleAgentClient == null) {
            return ToolDispatchResult.silent("✗ 日程工具未启用(A2aAgent 未装配,ScheduleAgentClient bean 缺失)");
        }
        if (!"delegate_to_schedule_agent".equals(tub.getName())) {
            return ToolDispatchResult.silent("✗ 未知工具:" + tub.getName() + "(伴偶只暴露 delegate_to_schedule_agent)");
        }
        Map<String, Object> input = tub.getInput() == null ? Map.of() : tub.getInput();
        String userText = str(input, "userText");
        if (userText == null || userText.isBlank()) {
            // LLM 漏填 userText:用原始 user 消息兜底(伴偶 ReAct 循环 context)
            userText = originalUserContent;
        }
        if (userText == null || userText.isBlank()) {
            return ToolDispatchResult.silent("✗ delegate_to_schedule_agent 缺 userText 参数");
        }
        String context = str(input, "context");
        try {
            String result = scheduleAgentClient.delegateToScheduleAgent(userText, context);
            return ToolDispatchResult.visible(result);
        } catch (Exception e) {
            // R-c6.2:@Tool 不 catch,异常透传到这里;此处 catch 转 silent(ReAct 卡死防御)
            log.warn("delegate_to_schedule_agent threw: {}", e.getMessage());
            return ToolDispatchResult.silent("✗ 工具执行异常(delegate_to_schedule_agent):" + e.getMessage());
        }
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : v.toString();
    }

    /** 统计 LLM 响应里 content block 类型分布,用于诊断 LLM 是否真的回了 ToolUseBlock。 */
    private static String contentBlockTypeSummary(List<ChatResponse> chunks) {
        if (chunks == null || chunks.isEmpty()) return "[]";
        Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (ChatResponse r : chunks) {
            if (r.getContent() == null) continue;
            for (ContentBlock b : r.getContent()) {
                counts.merge(b.getClass().getSimpleName(), 1, Integer::sum);
            }
        }
        return counts.toString();
    }
}
