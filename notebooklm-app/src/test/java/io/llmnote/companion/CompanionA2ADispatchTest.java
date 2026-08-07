package io.llmnote.companion;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.llmnote.companion.a2a.ScheduleAgentClient;
import io.llmnote.companion.config.CompanionProperties;
import io.llmnote.llm.ChatModelFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证:当 LLM 返回 ToolUseBlock(name=delegate_to_schedule_agent)时,CompanionChatService
 * 会通过 ScheduleAgentClient 调 A2aAgent,并把结果回灌到 ReAct 循环。
 * <p>
 * 不依赖真实 schedule-agent / 真实 LLM。用 mock ChatModelFactory 模拟
 * "模型决定调日程工具",用 mock ScheduleAgentClient 模拟微服务响应。
 * <p>
 * 单一工具模式(伴偶视角只暴露 delegate_to_schedule_agent),LLM 不直接面对 12 个底层 @Tool。
 */
@ExtendWith(MockitoExtension.class)
class CompanionA2ADispatchTest {

    @Mock CompanionRepository companionRepository;
    @Mock CompanionMessageRepository messageRepository;
    @Mock CompanionMemoryService memoryService;
    @Mock CompanionIntimacyService intimacyService;
    @Mock ChatModelFactory modelFactory;
    @Mock CompanionVisionService visionService;
    @Mock ScheduleAgentClient scheduleAgentClient;

    CompanionProperties properties;
    ObjectMapper objectMapper;
    CompanionChatService service;

    @BeforeEach
    void setup() {
        properties = new CompanionProperties();
        objectMapper = new ObjectMapper();
        service = new CompanionChatService(companionRepository, messageRepository,
                memoryService, intimacyService, modelFactory, visionService,
                properties, objectMapper, scheduleAgentClient);
    }

    @AfterEach
    void cleanup() {
        ScheduleAgentClient.clearUserId();
    }

    @Test
    void when_llm_emits_delegateScheduleAgent_toolUse_then_service_dispatches_with_userText() {
        Companion c = new Companion();
        c.setId(7L); c.setName("小米"); c.setDefaultModel("glm-4.7-flash");
        c.setGender(io.llmnote.companion.enums.Gender.FEMALE);
        when(companionRepository.findByIdAndUserId(7L, "u:1")).thenReturn(java.util.Optional.of(c));
        when(messageRepository.save(any())).thenAnswer(inv -> {
            CompanionMessage m = inv.getArgument(0);
            if (m.getId() == null) m.setId(System.nanoTime());
            return m;
        });
        when(messageRepository.findVisible(any(), any(), any())).thenReturn(new ArrayList<>());
        when(memoryService.search(anyString(), any(), anyString())).thenReturn(List.of());
        when(modelFactory.isToolsCapable(anyString())).thenReturn(false);
        when(modelFactory.toolsCapableModel()).thenReturn("qwen-turbo");
        when(modelFactory.streamTextWithToolsFallback(anyString(), any(), isNull(), anyList()))
                .thenAnswer(inv -> List.of(ChatResponse.builder()
                        .content(List.of(
                                ToolUseBlock.builder()
                                        .id("call-1")
                                        .name("delegate_to_schedule_agent")
                                        .input(Map.of("userText", "今天 14:00 加个产品评审"))
                                        .build()))
                        .build()));
        when(intimacyService.recordInteraction(anyString(), any()))
                .thenReturn(new CompanionIntimacyService.RecordResult(new CompanionIntimacy(), List.of()));

        // mock schedule-agent 透传:userText → 返回成功文本
        doAnswer(inv -> "✓ 已创建事件:产品评审(2026-08-07 14:00,P1)")
                .when(scheduleAgentClient).delegateToScheduleAgent(eq("今天 14:00 加个产品评审"), isNull());

        var sink = new CapturingSink();
        Long aiMsgId = service.handleUserMessage("u:1", 7L, "今天 14:00 加个产品评审", null, null, sink);

        assertThat(aiMsgId).as("assistant 消息应落库").isNotNull();
        assertThat(sink.errors).as("不应有错误").isEmpty();
        verify(scheduleAgentClient, org.mockito.Mockito.atLeast(1))
                .delegateToScheduleAgent(eq("今天 14:00 加个产品评审"), isNull());
    }

    @Test
    void when_llm_omits_userText_then_service_falls_back_to_original_user_content() {
        Companion c = new Companion();
        c.setId(7L); c.setName("小米"); c.setDefaultModel("glm-4.7-flash");
        c.setGender(io.llmnote.companion.enums.Gender.FEMALE);
        when(companionRepository.findByIdAndUserId(7L, "u:1")).thenReturn(java.util.Optional.of(c));
        when(messageRepository.save(any())).thenAnswer(inv -> {
            CompanionMessage m = inv.getArgument(0);
            if (m.getId() == null) m.setId(System.nanoTime());
            return m;
        });
        when(messageRepository.findVisible(any(), any(), any())).thenReturn(new ArrayList<>());
        when(memoryService.search(anyString(), any(), anyString())).thenReturn(List.of());
        when(modelFactory.isToolsCapable(anyString())).thenReturn(false);
        when(modelFactory.toolsCapableModel()).thenReturn("qwen-turbo");
        when(modelFactory.streamTextWithToolsFallback(anyString(), any(), isNull(), anyList()))
                .thenAnswer(inv -> List.of(ChatResponse.builder()
                        .content(List.of(
                                ToolUseBlock.builder()
                                        .id("call-2")
                                        .name("delegate_to_schedule_agent")
                                        .input(Map.of())   // LLM 没填 userText
                                        .build()))
                        .build()));
        when(intimacyService.recordInteraction(anyString(), any()))
                .thenReturn(new CompanionIntimacyService.RecordResult(new CompanionIntimacy(), List.of()));

        doAnswer(inv -> "✓ fallback OK")
                .when(scheduleAgentClient).delegateToScheduleAgent(eq("看看我今天有什么日程"), isNull());

        var sink = new CapturingSink();
        service.handleUserMessage("u:1", 7L, "看看我今天有什么日程", null, null, sink);

        // 兜底:LLM 漏填 userText,service 用原始 user 消息当 userText
        verify(scheduleAgentClient, org.mockito.Mockito.atLeast(1))
                .delegateToScheduleAgent(eq("看看我今天有什么日程"), isNull());
    }

    static class CapturingSink implements CompanionChatService.DeltaSink {
        String fullText; String model; Long inTok; Long outTok; String requestId;
        List<String> errors = new ArrayList<>();
        @Override public void onDelta(String delta) {}
        @Override public void onDone(String fullText, String model, Long inTok, Long outTok, String requestId) {
            this.fullText = fullText; this.model = model; this.inTok = inTok; this.outTok = outTok; this.requestId = requestId;
        }
        @Override public void onError(String error) { errors.add(error); }
    }
}
