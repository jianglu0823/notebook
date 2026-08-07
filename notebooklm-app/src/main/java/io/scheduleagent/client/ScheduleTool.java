package io.scheduleagent.client;

import java.util.Map;

/**
 * STUB — 临时占位,仅用于让旧的 {@code io.llmnote.companion.a2a.ScheduleAgentTools}
 * 在 client-sdk 模块被删除后仍能编译。
 * <p>
 * B5 会整体删除:
 * <ul>
 *   <li>本 stub(临时)
 *   <li>旧的 {@code io.llmnote.companion.a2a.ScheduleAgentTools}(被基于 A2aAgent 的新版替代)
 *   <li>{@code CompanionChatService} 里的 {@code ScheduleAgentTools} 注入点
 * </ul>
 * B5 完成后,所有对 schedule-agent 的调用都走 {@code io.llmnote.companion.a2a.client.ScheduleAgent}(A2aAgent)。
 * <p>
 * 本类的方法都抛 {@link UnsupportedOperationException},因为旧代码路径在 B4~B5 期间
 * 不应被实际调用(测试/构建可通过,但生产路径已通过 application.yml 的
 * {@code agentscope.a2a.client.enabled} 控制走 A2A)。
 */
public class ScheduleTool {

    private final String baseUrl;
    private final Long userId;

    public ScheduleTool(String baseUrl, Long userId) {
        this.baseUrl = baseUrl;
        this.userId = userId;
    }

    public String getBaseUrl() { return baseUrl; }
    public Long getUserId() { return userId; }

    public String createEvent(Map<String, Object> request) {
        throw new UnsupportedOperationException(
                "ScheduleTool stub — old REST path is removed; use A2aAgent (B5 will delete this class)");
    }

    public String getEvent(String eventId) {
        throw new UnsupportedOperationException(
                "ScheduleTool stub — old REST path is removed; use A2aAgent (B5 will delete this class)");
    }

    public String listEvents(Map<String, Object> query) {
        throw new UnsupportedOperationException(
                "ScheduleTool stub — old REST path is removed; use A2aAgent (B5 will delete this class)");
    }

    public String updateEvent(String eventId, Map<String, Object> request) {
        throw new UnsupportedOperationException(
                "ScheduleTool stub — old REST path is removed; use A2aAgent (B5 will delete this class)");
    }

    public String deleteEvent(String eventId) {
        throw new UnsupportedOperationException(
                "ScheduleTool stub — old REST path is removed; use A2aAgent (B5 will delete this class)");
    }
}
