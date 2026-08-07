package io.llmnote.companion.a2a;

import io.agentscope.core.a2a.agent.A2aAgent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 伴偶 → schedule-agent 的"单 @Tool 透传"桥接(c6 R-c6.1):
 * 把整个 schedule-agent 当作伴偶可调用的单 @Tool — {@code delegate_to_schedule_agent},
 * userText 透传给 schedule-agent 的 ReAct Agent,后者自主用 12 个 @Tool 完成
 * 日程/专注/同步等操作,把结构化结果回给伴偶。
 * <p>
 * 由 {@code @ConditionalOnBean(A2aAgent.class)} 守护:本机无 Nacos 3.x 不可达时
 * (A2aAgent bean 未装配)→ 本类不装配 → CompanionChatService 走"无工具"分支,
 * 伴偶照常聊天,但不能调日程功能。
 * <p>
 * userId 通过 {@link #setUserId} 在每次 LLM 调用前由伴偶服务 set,@Tool 内部读后
 * 以 query 前缀 {@code "[userId=N] "} 透传;schedule-agent 端 system prompt 解析
 * (R-c3.1 / R-c5.2 复用);call 完 clear。
 * <p>
 * 错误处理(R-c6.2):不 catch 异常吞掉,直接抛给 ReAct 循环,后者标记 silent 结果
 * 并 break 卡死防御。
 */
@Slf4j
@Component
@ConditionalOnBean(A2aAgent.class)
public class ScheduleAgentClient {

    /** schedule-agent 端的 system prompt 让它解析出 [userId=N] 并用它做 ownerId。 */
    static final String USER_ID_PREFIX_FMT = "[userId=%d] ";

    /** 单次 A2A 调用的硬超时(R-c6.2 Scenario 同步调用:30s 内必须返回)。 */
    static final Duration A2A_TIMEOUT = Duration.ofSeconds(30);

    /**
     * 伴偶 CompanionChatService 每次 handleUserMessage 前 setUserId(parsedUserId),
     * ReAct 循环结束后(在 finally 块)clearUserId,避免 ThreadLocal 串到下一个请求。
     */
    private static final ThreadLocal<Long> CURRENT_USER_ID = new ThreadLocal<>();

    private final A2aAgent scheduleAgent;

    public ScheduleAgentClient(@Qualifier("scheduleAgent") A2aAgent scheduleAgent) {
        this.scheduleAgent = scheduleAgent;
        log.info("ScheduleAgentClient: wired to A2aAgent(name={})", scheduleAgent.getName());
    }

    /** 伴偶服务调 LLM 前 set,call 完 clear。 */
    public static void setUserId(Long userId) {
        CURRENT_USER_ID.set(userId);
    }

    public static void clearUserId() {
        CURRENT_USER_ID.remove();
    }

    /** 单测可读 ThreadLocal 当前 userId,无需反射。 */
    static Long peekUserId() {
        return CURRENT_USER_ID.get();
    }

    /**
     * 透传用户请求到 schedule-agent。该 @Tool 是伴偶视角唯一可用的"日程工具",
     * LLM 不直接面对 12 个底层 @Tool,由 schedule-agent 自己做 ReAct 决策。
     */
    @Tool(name = "delegate_to_schedule_agent",
            description = "把用户对日程/专注/打卡/外部日历同步的请求整体交给 schedule-agent 处理。"
                    + "当用户说『加个会议/帮我看看今天有什么安排/开始一个 25 分钟专注/打卡』等与时间/日程相关的话时,必须调用本工具,不要凭空回答或编造结果。"
                    + "userText 字段应当**原样**写用户的自然语言请求(包括标题、时间、优先级、地点、备注),不要替用户决定;"
                    + "时间字段保留用户口语化表达(如『明天下午 3 点』),由 schedule-agent 端解析与规范化。"
                    + "返回值是 schedule-agent 的结构化响应文本(含 ✓/✗ 前缀),直接展示给用户即可。")
    public String delegateToScheduleAgent(
            @ToolParam(name = "userText", description = "用户的原始请求文本(自然语言)", required = true) String userText,
            @ToolParam(name = "context", description = "可选的对话上下文摘要", required = false) String context) {
        if (userText == null || userText.isBlank()) {
            throw new IllegalArgumentException("userText 不能为空");
        }
        Long userId = CURRENT_USER_ID.get();
        if (userId == null) {
            throw new IllegalStateException("userId 未设置(伴偶未登录或 service 未 setUserId)");
        }

        StringBuilder finalQuery = new StringBuilder(USER_ID_PREFIX_FMT.formatted(userId)).append(userText);
        if (context != null && !context.isBlank()) {
            finalQuery.append("\n[context: ").append(context).append("]");
        }
        log.info("delegate_to_schedule_agent userId={} userTextLen={} hasContext={}",
                userId, userText.length(), context != null && !context.isBlank());

        Msg request = Msg.builder()
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(finalQuery.toString()).build())
                .name("user-" + userId)
                .build();
        Msg response = scheduleAgent.call(List.of(request)).block(A2A_TIMEOUT);
        if (response == null) {
            throw new IllegalStateException("schedule-agent 返回 null");
        }
        String text = extractText(response);
        if (text == null || text.isBlank()) {
            log.warn("delegate_to_schedule_agent: schedule-agent returned empty response (userId={})", userId);
            throw new IllegalStateException("schedule-agent 无响应内容");
        }
        log.info("delegate_to_schedule_agent OK userId={} respLen={}", userId, text.length());
        return text;
    }

    /** 从 Msg.content 提取所有 TextBlock 的文本,newline 连接;若没有则返回 null。 */
    private static String extractText(Msg msg) {
        if (msg == null || msg.getContent() == null) return null;
        StringBuilder sb = new StringBuilder();
        for (ContentBlock b : msg.getContent()) {
            if (b instanceof TextBlock tb && tb.getText() != null) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(tb.getText());
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }
}
