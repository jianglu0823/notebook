package io.llmnote.companion;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.llmnote.auth.ForbiddenException;
import io.llmnote.companion.config.CompanionProperties;
import io.llmnote.companion.dto.Milestone;
import io.llmnote.llm.ChatModelFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * CompanionChatService 集成测试:模拟一个 LLM 一次返回 "hi",验证:
 * 1. user 消息 + assistant 消息都落库
 * 2. DeltaSink.onDone 被调用,fullText/model 正确
 * 3. 亲密度被记录,milestone 推到 sink
 * 4. 跨用户访问抛 ForbiddenException(404)
 */
@ExtendWith(MockitoExtension.class)
class CompanionChatServiceIntegrationTest {

    @Mock CompanionRepository companionRepository;
    @Mock CompanionMessageRepository messageRepository;
    @Mock CompanionMemoryService memoryService;
    @Mock CompanionIntimacyService intimacyService;
    @Mock ChatModelFactory modelFactory;
    @Mock CompanionVisionService visionService;
    CompanionProperties properties;
    ObjectMapper objectMapper;
    CompanionChatService service;

    @BeforeEach
    void setup() {
        properties = new CompanionProperties();
        objectMapper = new ObjectMapper();
        // 用一个"干跑"milvus 客户端避免真的去连 Milvus
        service = new CompanionChatService(companionRepository, messageRepository,
                memoryService, intimacyService, modelFactory, visionService,
                properties, objectMapper);
    }

    @Test
    void handleUserMessage_otherUsersCompanion_throwsForbidden() {
        when(companionRepository.findByIdAndUserId(7L, "u:1")).thenReturn(java.util.Optional.empty());
        var sink = new CapturingSink();
        assertThatThrownBy(() -> service.handleUserMessage("u:1", 7L, "hi", null, null, sink))
                .isInstanceOf(ForbiddenException.class);
        assertThat(sink.errors).isEmpty();
    }

    @Test
    void blankContent_returnsErrorAndNullMessageId() {
        when(companionRepository.findByIdAndUserId(7L, "u:1")).thenReturn(java.util.Optional.of(new Companion()));
        var sink = new CapturingSink();
        Long mid = service.handleUserMessage("u:1", 7L, "  ", null, null, sink);
        assertThat(mid).isNull();
        assertThat(sink.errors).anyMatch(e -> e.contains("content"));
    }

    @Test
    void handleUserMessage_streamsAndPersistsAssistantReply() {
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
        when(modelFactory.normalize(anyString())).thenReturn("glm-4.7-flash");
        when(modelFactory.streamTextWithFallback(anyString(), any()))
                .thenReturn(List.of(ChatResponse.builder()
                        .content(List.of(TextBlock.builder().text("你好,我是 AI 小米。").build()))
                        .build()));
        when(intimacyService.recordInteraction(anyString(), any()))
                .thenReturn(new CompanionIntimacyService.RecordResult(new CompanionIntimacy(), List.of()));

        var sink = new CapturingSink();
        Long aiMsgId = service.handleUserMessage("u:1", 7L, "你好", null, null, sink);

        assertThat(aiMsgId).isNotNull();
        assertThat(sink.fullText).isEqualTo("你好,我是 AI 小米。");
        assertThat(sink.model).isEqualTo("glm-4.7-flash");
        // user 消息 + assistant 消息 = 2 次 save
        org.mockito.Mockito.verify(messageRepository, org.mockito.Mockito.times(2)).save(any());
    }

    @Test
    void handleUserMessage_milestones_pushedToSink() {
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
        when(modelFactory.normalize(anyString())).thenReturn("glm-4.7-flash");
        when(modelFactory.streamTextWithFallback(anyString(), any()))
                .thenReturn(List.of(ChatResponse.builder()
                        .content(List.of(TextBlock.builder().text("嗯嗯").build()))
                        .build()));
        when(intimacyService.recordInteraction(anyString(), any()))
                .thenReturn(new CompanionIntimacyService.RecordResult(new CompanionIntimacy(),
                        List.of(new Milestone("milestone_7", "7 天啦"))));

        var sink = new CapturingSink();
        service.handleUserMessage("u:1", 7L, "早", null, null, sink);

        assertThat(sink.milestones).extracting(Milestone::kind).containsExactly("milestone_7");
    }

    static class CapturingSink implements CompanionChatService.DeltaSink {
        String fullText; String model; Long inTok; Long outTok; String requestId;
        List<String> errors = new ArrayList<>();
        List<Milestone> milestones = new ArrayList<>();
        @Override public void onDelta(String delta) {}
        @Override public void onDone(String fullText, String model, Long inTok, Long outTok, String requestId) {
            this.fullText = fullText; this.model = model; this.inTok = inTok; this.outTok = outTok; this.requestId = requestId;
        }
        @Override public void onError(String error) { errors.add(error); }
        @Override public void onMilestones(List<Milestone> ms) { if (ms != null) milestones.addAll(ms); }
    }
}
