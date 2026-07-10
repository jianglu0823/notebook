package io.llmnote.world;

import io.llmnote.auth.Principal;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 智能体小世界入口。居民(智能体)CRUD + 1:1 对话 + 话题会议 + 自主行动世界设置 + 实时事件流。
 * 居民与记忆<b>全局共享</b>(斯坦福小镇模式),会议/对话按发起人 ownerId 归档。
 */
@RestController
@RequestMapping("/api/world")
@RequiredArgsConstructor
public class WorldController {

    private final AgentEmployeeService employeeService;
    private final MeetingService meetingService;
    private final AgentChatService chatService;
    private final GroupChatService groupChatService;
    private final WorldSettingsService settingsService;
    private final AgentActionRepository actionRepo;
    private final AgentMemoryRepository memoryRepo;

    // ---- 居民(全局共享) ----

    @GetMapping("/agents")
    public List<AgentEmployee> listAgents() {
        return employeeService.list(AgentEmployeeService.WORLD);
    }

    /** 小黑屋:被软删除的居民。 */
    @GetMapping("/jail")
    public List<AgentEmployee> listJailed() {
        return employeeService.jailed();
    }

    /** 具名地点地图:前端画建筑/落位的单一数据源(世界尺寸 + 地点清单)。 */
    @GetMapping("/places")
    public TownResp places() {
        return new TownResp(TownMap.WORLD_W, TownMap.WORLD_H, TownMap.all());
    }

    @PostMapping("/agents")
    public AgentEmployee createAgent(@RequestBody AgentEmployee body, Principal principal) {
        return employeeService.create(body, principal.ownerId());
    }

    @PutMapping("/agents/{id}")
    public AgentEmployee updateAgent(@PathVariable Long id, @RequestBody AgentEmployee body, Principal principal) {
        employeeService.requireManage(employeeService.get(id), principal);
        return employeeService.update(id, body);
    }

    /** 「删除」= 软删除:关进小黑屋,数据保留。 */
    @DeleteMapping("/agents/{id}")
    public void deleteAgent(@PathVariable Long id, Principal principal) {
        employeeService.requireManage(employeeService.get(id), principal);
        employeeService.jail(id);
    }

    /** 从小黑屋释放。 */
    @PostMapping("/agents/{id}/release")
    public AgentEmployee releaseAgent(@PathVariable Long id) {
        return employeeService.release(id);
    }

    // ---- 1:1 对话 ----

    @GetMapping("/agents/{id}/chat")
    public List<AgentChatMsg> chatHistory(@PathVariable Long id, Principal principal) {
        return chatService.history(id, principal.ownerId());
    }

    @PostMapping("/agents/{id}/chat")
    public AgentChatMsg chat(@PathVariable Long id, @RequestBody ChatReq req, Principal principal) {
        return chatService.chat(id, principal.ownerId(), req == null ? null : req.getMessage());
    }

    // ---- 世界设置(自主行动总开关/间隔/模型) ----

    @GetMapping("/settings")
    public WorldSettings getSettings() {
        return settingsService.get();
    }

    @PutMapping("/settings")
    public WorldSettings updateSettings(@RequestBody SettingsReq req) {
        return settingsService.update(
                req == null ? null : req.getAutonomousEnabled(),
                req == null ? null : req.getIntervalSeconds(),
                req == null ? null : req.getModel());
    }

    // ---- 实时事件流(自主行动 + 记忆,供地图 feed) ----

    @GetMapping("/feed")
    public List<FeedItem> feed(@RequestParam(required = false) Long since) {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(10);
        List<FeedItem> out = new ArrayList<>();
        for (AgentAction a : actionRepo.findByCreatedAtAfterOrderByIdAsc(cutoff)) {
            out.add(FeedItem.of("action", a.getId(), a.getAgentId(), a.getType(),
                    a.getContent(), a.getTargetAgentId(), a.getPlace(), a.getScene(), a.getCreatedAt()));
        }
        for (AgentMemory mem : memoryRepo.findByCreatedAtAfterOrderByIdAsc(cutoff)) {
            out.add(FeedItem.of("memory", mem.getId(), mem.getAgentId(), mem.getKind(),
                    mem.getContent(), mem.getRelatedAgentId(), null, null, mem.getCreatedAt()));
        }
        out.sort(Comparator.comparing(FeedItem::getCreatedAt));
        if (since != null) out.removeIf(f -> f.getCreatedAt() != null
                && f.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() <= since);
        return out;
    }

    // ---- 村民动态(全量流水,前端搜索/筛选) ----

    /** 最近的行动 + 记忆合流(至多各 300 条),按时间倒序,供「村民动态」面板搜索筛选。 */
    @GetMapping("/activity")
    public List<FeedItem> activity() {
        List<FeedItem> out = new ArrayList<>();
        for (AgentAction a : actionRepo.findTop300ByOrderByIdDesc()) {
            out.add(FeedItem.of("action", a.getId(), a.getAgentId(), a.getType(),
                    a.getContent(), a.getTargetAgentId(), a.getPlace(), a.getScene(), a.getCreatedAt()));
        }
        for (AgentMemory mem : memoryRepo.findTop300ByOrderByIdDesc()) {
            out.add(FeedItem.of("memory", mem.getId(), mem.getAgentId(), mem.getKind(),
                    mem.getContent(), mem.getRelatedAgentId(), null, null, mem.getCreatedAt()));
        }
        out.sort(Comparator.comparing(FeedItem::getCreatedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return out;
    }

    // ---- 会议 ----

    @PostMapping("/meetings")
    public AgentMeeting createMeeting(@RequestBody MeetingReq req, Principal principal) {
        return meetingService.create(principal.ownerId(),
                req == null ? null : req.getTopic(),
                req == null ? null : req.getParticipantIds(),
                req == null ? null : req.getMaxRounds(),
                req == null ? null : req.getModel());
    }

    @GetMapping("/meetings")
    public List<AgentMeeting> listMeetings(Principal principal) {
        return meetingService.list(principal.ownerId());
    }

    @GetMapping("/meetings/{id}")
    public AgentMeeting getMeeting(@PathVariable Long id, Principal principal) {
        return meetingService.get(id, principal.ownerId());
    }

    @DeleteMapping("/meetings/{id}")
    public void deleteMeeting(@PathVariable Long id, Principal principal) {
        meetingService.delete(id, principal.ownerId());
    }

    // ---- 群聊(交互式多智能体,支持中途增减成员) ----

    @PostMapping("/groups")
    public AgentGroupChat createGroup(@RequestBody GroupReq req, Principal principal) {
        return groupChatService.create(principal.ownerId(),
                req == null ? null : req.getTitle(),
                req == null ? null : req.getMemberIds(),
                req == null ? null : req.getModel());
    }

    @GetMapping("/groups")
    public List<AgentGroupChat> listGroups(Principal principal) {
        return groupChatService.list(principal.ownerId());
    }

    @GetMapping("/groups/{id}")
    public AgentGroupChat getGroup(@PathVariable Long id, Principal principal) {
        return groupChatService.get(id, principal.ownerId());
    }

    @GetMapping("/groups/{id}/messages")
    public List<AgentGroupMsg> groupMessages(@PathVariable Long id, Principal principal) {
        return groupChatService.history(id, principal.ownerId());
    }

    @PostMapping("/groups/{id}/messages")
    public List<AgentGroupMsg> postGroup(@PathVariable Long id, @RequestBody ChatReq req, Principal principal) {
        return groupChatService.post(id, principal.ownerId(), req == null ? null : req.getMessage());
    }

    @PostMapping("/groups/{id}/members/{agentId}")
    public AgentGroupChat addGroupMember(@PathVariable Long id, @PathVariable Long agentId, Principal principal) {
        return groupChatService.addMember(id, principal.ownerId(), agentId);
    }

    @DeleteMapping("/groups/{id}/members/{agentId}")
    public AgentGroupChat removeGroupMember(@PathVariable Long id, @PathVariable Long agentId, Principal principal) {
        return groupChatService.removeMember(id, principal.ownerId(), agentId);
    }

    @DeleteMapping("/groups/{id}")
    public void deleteGroup(@PathVariable Long id, Principal principal) {
        groupChatService.delete(id, principal.ownerId());
    }

    @Data
    public static class GroupReq {
        private String title;
        private List<Long> memberIds;
        private String model;
    }

    @Data
    public static class MeetingReq {
        private String topic;
        private List<Long> participantIds;
        private Integer maxRounds;
        private String model;
    }

    @Data
    public static class ChatReq {
        private String message;
    }

    @Data
    public static class SettingsReq {
        private Boolean autonomousEnabled;
        private Integer intervalSeconds;
        private String model;
    }

    /** feed 统一条目:动作/记忆合流,前端据此驱动地图动画与时间线。 */
    @Data
    public static class FeedItem {
        private String source;      // action | memory
        private Long id;
        private Long agentId;
        private String kind;        // action.type 或 memory.kind
        private String content;
        private Long targetAgentId;
        private String place;       // 行动发生地点 key(memory 为空)
        private String scene;       // 场景短标题(memory 为空)
        private LocalDateTime createdAt;

        static FeedItem of(String source, Long id, Long agentId, String kind,
                           String content, Long targetAgentId, String place, String scene,
                           LocalDateTime createdAt) {
            FeedItem f = new FeedItem();
            f.source = source;
            f.id = id;
            f.agentId = agentId;
            f.kind = kind;
            f.content = content;
            f.targetAgentId = targetAgentId;
            f.place = place;
            f.scene = scene;
            f.createdAt = createdAt;
            return f;
        }
    }

    /** /places 响应:世界尺寸 + 地点清单。 */
    public record TownResp(double worldW, double worldH, List<TownMap.Place> places) {}
}
