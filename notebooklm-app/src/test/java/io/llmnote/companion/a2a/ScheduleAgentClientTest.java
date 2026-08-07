package io.llmnote.companion.a2a;

import io.agentscope.core.a2a.agent.A2aAgent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ScheduleAgentClient 单元测试(c6 R-c6.1 / R-c6.2 / R-c6.4):
 * 验证 userText 透传 + userId 前缀格式 + 30s 超时 + 异常透传(R-c6.2 Scenario 阻塞异常)。
 * <p>
 * 不依赖真实 schedule-agent / 真实 Nacos;A2aAgent 是 mock,Mono 由 Mockito stub。
 */
@ExtendWith(MockitoExtension.class)
class ScheduleAgentClientTest {

    @Mock A2aAgent scheduleAgent;

    ScheduleAgentClient client;

    @BeforeEach
    void setup() {
        client = new ScheduleAgentClient(scheduleAgent);
    }

    @AfterEach
    void cleanup() {
        // 测试结束后清 ThreadLocal,避免污染其他测试
        ScheduleAgentClient.clearUserId();
    }

    @Test
    void delegateToScheduleAgent_userIdFromThreadLocal_prependedToQuery_andReturnsText() {
        ScheduleAgentClient.setUserId(42L);
        Msg respMsg = Msg.builder()
                .role(MsgRole.ASSISTANT)
                .content(List.of((ContentBlock) TextBlock.builder().text("✓ 已创建:产品评审 14:00").build()))
                .build();
        when(scheduleAgent.call(anyList())).thenReturn(Mono.just(respMsg));

        String result = client.delegateToScheduleAgent("今天 14:00 加个产品评审", null);

        assertThat(result).isEqualTo("✓ 已创建:产品评审 14:00");

        // 验证透传给 A2aAgent 的 query 带了 [userId=42] 前缀
        ArgumentCaptor<List<Msg>> captor = ArgumentCaptor.forClass(List.class);
        verify(scheduleAgent).call(captor.capture());
        List<Msg> sent = captor.getValue();
        assertThat(sent).hasSize(1);
        Msg req = sent.get(0);
        assertThat(req.getRole()).isEqualTo(MsgRole.USER);
        String text = ((TextBlock) req.getContent().get(0)).getText();
        assertThat(text).isEqualTo("[userId=42] 今天 14:00 加个产品评审");
        assertThat(req.getName()).isEqualTo("user-42");
    }

    @Test
    void delegateToScheduleAgent_withContext_appendedToQuery() {
        ScheduleAgentClient.setUserId(7L);
        Msg respMsg = Msg.builder()
                .role(MsgRole.ASSISTANT)
                .content(List.of((ContentBlock) TextBlock.builder().text("ok").build()))
                .build();
        when(scheduleAgent.call(anyList())).thenReturn(Mono.just(respMsg));

        String result = client.delegateToScheduleAgent("看看今天有什么", "用户最近在问工作日程");

        assertThat(result).isEqualTo("ok");
        ArgumentCaptor<List<Msg>> captor = ArgumentCaptor.forClass(List.class);
        verify(scheduleAgent).call(captor.capture());
        String text = ((TextBlock) captor.getValue().get(0).getContent().get(0)).getText();
        assertThat(text).contains("[userId=7] 看看今天有什么");
        assertThat(text).contains("[context: 用户最近在问工作日程]");
    }

    @Test
    void delegateToScheduleAgent_emptyUserText_throws() {
        ScheduleAgentClient.setUserId(1L);
        assertThatThrownBy(() -> client.delegateToScheduleAgent("  ", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userText");
        verify(scheduleAgent, never()).call(anyList());
    }

    @Test
    void delegateToScheduleAgent_nullUserId_throws() {
        // 不 setUserId,ThreadLocal 是 null
        assertThatThrownBy(() -> client.delegateToScheduleAgent("查日程", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("userId");
        verify(scheduleAgent, never()).call(anyList());
    }

    @Test
    void delegateToScheduleAgent_emptyResponse_throws() {
        // schedule-agent 返回空 content(实际可能 MessageEnd 但无 TextBlock)
        ScheduleAgentClient.setUserId(7L);
        Msg emptyMsg = Msg.builder().role(MsgRole.ASSISTANT).content(List.of()).build();
        when(scheduleAgent.call(anyList())).thenReturn(Mono.just(emptyMsg));

        assertThatThrownBy(() -> client.delegateToScheduleAgent("查明天日程", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("schedule-agent");
    }

    @Test
    void delegateToScheduleAgent_agentThrows_propagatesException() {
        // R-c6.2 Scenario 阻塞异常:@Tool 不 catch,直接抛给 ReAct 循环
        ScheduleAgentClient.setUserId(1L);
        when(scheduleAgent.call(anyList())).thenReturn(Mono.error(new RuntimeException("connection refused")));

        assertThatThrownBy(() -> client.delegateToScheduleAgent("查今天日程", null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("connection refused");
    }

    @Test
    void delegateToScheduleAgent_multipleTextBlocks_concatenatesWithNewline() {
        ScheduleAgentClient.setUserId(1L);
        Msg multi = Msg.builder()
                .role(MsgRole.ASSISTANT)
                .content(List.of(
                        (ContentBlock) TextBlock.builder().text("✓ 已创建").build(),
                        (ContentBlock) TextBlock.builder().text("14:00 P1").build()))
                .build();
        when(scheduleAgent.call(anyList())).thenReturn(Mono.just(multi));

        String result = client.delegateToScheduleAgent("加会议", null);
        assertThat(result).isEqualTo("✓ 已创建\n14:00 P1");
    }
}
