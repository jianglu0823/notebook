package io.llmnote.companion.a2a;

import io.agentscope.core.a2a.agent.A2aAgent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ScheduleAgentTools 单元测试:把 LLM 视角的"调日程"请求透传到 A2aAgent,
 * 验证 query 前缀格式 + 文本提取 + 异常降级。
 * <p>
 * 不依赖真实 schedule-agent / 真实 Nacos;A2aAgent 是 mock,Mono 由 Mockito stub。
 */
@ExtendWith(MockitoExtension.class)
class ScheduleAgentToolsTest {

    @Mock A2aAgent scheduleAgent;

    ScheduleAgentTools tools;

    @BeforeEach
    void setup() {
        tools = new ScheduleAgentTools(scheduleAgent);
    }

    @Test
    void invokeScheduleAgent_prependsUserIdPrefix_toQuery_andReturnsText() {
        Msg respMsg = Msg.builder()
                .role(MsgRole.ASSISTANT)
                .content(List.of((ContentBlock) TextBlock.builder().text("✓ 已创建:产品评审 14:00").build()))
                .build();
        when(scheduleAgent.call(anyList())).thenReturn(Mono.just(respMsg));

        String result = tools.invokeScheduleAgent(42L, "今天 14:00 加个产品评审");

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
    void invokeScheduleAgent_emptyQuery_returnsErrorWithoutCallingAgent() {
        String result = tools.invokeScheduleAgent(1L, "  ");
        assertThat(result).isEqualTo("✗ query 不能为空");
        verify(scheduleAgent, never()).call(anyList());
    }

    @Test
    void invokeScheduleAgent_nullUserId_returnsError() {
        String result = tools.invokeScheduleAgent(null, "看看日程");
        assertThat(result).isEqualTo("✗ userId 不能为空(未登录态不支持日程功能)");
        verify(scheduleAgent, never()).call(anyList());
    }

    @Test
    void invokeScheduleAgent_emptyResponse_returnsErrorMessage() {
        // schedule-agent 返回空 content(实际可能 MessageEnd 但无 TextBlock)
        Msg emptyMsg = Msg.builder().role(MsgRole.ASSISTANT).content(List.of()).build();
        when(scheduleAgent.call(anyList())).thenReturn(Mono.just(emptyMsg));

        String result = tools.invokeScheduleAgent(7L, "查明天日程");
        assertThat(result).isEqualTo("✗ schedule-agent 无响应内容");
    }

    @Test
    void invokeScheduleAgent_agentThrows_returnsErrorMessage() {
        when(scheduleAgent.call(anyList())).thenReturn(Mono.error(new RuntimeException("connection refused")));

        String result = tools.invokeScheduleAgent(1L, "查今天日程");
        assertThat(result).isEqualTo("✗ 调 schedule-agent 失败:connection refused");
    }

    @Test
    void invokeScheduleAgent_multipleTextBlocks_concatenatesWithNewline() {
        Msg multi = Msg.builder()
                .role(MsgRole.ASSISTANT)
                .content(List.of(
                        (ContentBlock) TextBlock.builder().text("✓ 已创建").build(),
                        (ContentBlock) TextBlock.builder().text("14:00 P1").build()))
                .build();
        when(scheduleAgent.call(anyList())).thenReturn(Mono.just(multi));

        String result = tools.invokeScheduleAgent(1L, "加会议");
        assertThat(result).isEqualTo("✓ 已创建\n14:00 P1");
    }
}
