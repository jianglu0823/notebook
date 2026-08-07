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

import java.util.List;

/**
 * 伴偶 → schedule-agent 的"单 @Tool 透传"桥接(c6 R-c6.1):
 * 把整个 schedule-agent 当作伴偶可调用的单 @Tool — {@code invoke_schedule_agent},
 * query 透传给 schedule-agent 的 ReAct Agent,后者自主用 12 个 @Tool 完成
 * 日程/专注/同步等操作,把结构化结果回给伴偶。
 * <p>
 * 由 {@code @ConditionalOnBean(A2aAgent.class)} 守护:本机无 Nacos 3.x 不可达时
 * (A2aAgent bean 未装配)→ 本类不装配 → CompanionChatService 走"无工具"分支,
 * 伴偶照常聊天,但不能调日程功能。
 * <p>
 * userId 通过 query 前缀传递(格式 {@code "[userId=N] <query>"}),
 * schedule-agent 端的 system prompt 让模型先回显 userId 再调工具;
 * 这样走 Nacos 3.x AI Registry + JSON-RPC,不需要把 userId 塞到 metadata。
 */
@Slf4j
@Component
@ConditionalOnBean(A2aAgent.class)
public class ScheduleAgentTools {

    /** schedule-agent 端的 system prompt 让它解析出 [userId=N] 并用它做 ownerId。 */
    static final String USER_ID_PREFIX_FMT = "[userId=%d] ";

    private final A2aAgent scheduleAgent;

    public ScheduleAgentTools(@Qualifier("scheduleAgent") A2aAgent scheduleAgent) {
        this.scheduleAgent = scheduleAgent;
        log.info("ScheduleAgentTools: wired to A2aAgent(name={})", scheduleAgent.getName());
    }

    /**
     * 透传查询到 schedule-agent。该 @Tool 是伴偶视角唯一可用的"日程工具",
     * LLM 不直接面对 12 个底层 @Tool,由 schedule-agent 自己做 ReAct 决策。
     */
    @Tool(name = "invoke_schedule_agent",
            description = "把用户关于日程/专注/打卡/外部日历同步的请求转发给 schedule-agent 微服务处理。"
                    + "当用户说『加个会议/帮我看看今天有什么安排/开始一个 25 分钟专注/打卡』等与时间/日程相关的话时,必须调用本工具,不要凭空回答或编造结果。"
                    + "query 字段应当**原样**写用户的自然语言请求(包括标题、时间、优先级、地点、备注),不要替用户决定;startTime/endTime 等时间字段应保留用户口语化表达(如『明天下午 3 点』),"
                    + "由 schedule-agent 端解析与规范化。"
                    + "返回值是 schedule-agent 的结构化响应文本(含 ✓/✗ 前缀),直接展示给用户即可。")
    public String invokeScheduleAgent(
            @ToolParam(name = "userId", description = "当前用户数字 id(后端 ownerId=u:N 中的 N)", required = true) Long userId,
            @ToolParam(name = "query", description = "用户原始请求,自然语言", required = true) String query) {
        if (query == null || query.isBlank()) {
            return "✗ query 不能为空";
        }
        if (userId == null) {
            return "✗ userId 不能为空(未登录态不支持日程功能)";
        }
        String finalQuery = USER_ID_PREFIX_FMT.formatted(userId) + query;
        log.info("invoke_schedule_agent userId={} queryLen={}", userId, query.length());

        Msg request = Msg.builder()
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(finalQuery).build())
                .name("user-" + userId)
                .build();
        try {
            Msg response = scheduleAgent.call(List.of(request)).block();
            String text = extractText(response);
            if (text == null || text.isBlank()) {
                log.warn("invoke_schedule_agent: schedule-agent returned empty response (userId={})", userId);
                return "✗ schedule-agent 无响应内容";
            }
            log.info("invoke_schedule_agent OK userId={} respLen={}", userId, text.length());
            return text;
        } catch (Exception e) {
            log.warn("invoke_schedule_agent FAIL userId={} err={}", userId, e.getMessage());
            return "✗ 调 schedule-agent 失败:" + e.getMessage();
        }
    }

    /** 从 Msg.content 提取第一个 TextBlock 的文本;若没有则返回 null。 */
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
