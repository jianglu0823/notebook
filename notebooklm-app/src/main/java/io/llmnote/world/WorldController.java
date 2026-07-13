package io.llmnote.world;

import io.llmnote.auth.Principal;
import io.llmnote.auth.User;
import io.llmnote.auth.UserRepository;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 智能体小世界入口。居民(智能体)CRUD + 1:1 对话 + 话题会议 + 自主行动世界设置 + 实时事件流。
 * 居民与记忆<b>全局共享</b>(智能体小镇模式),会议/对话按发起人 ownerId 归档。
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
    private final UserRepository userRepo;
    private final WorldDailyReportRepository reportRepo;
    private final AgentProductRepository productRepo;
    private final AgentCommentRepository commentRepo;
    private final RelationshipService relationshipService;
    private final SandboxService sandboxService;
    private final io.llmnote.llm.ZhipuMediaClient mediaClient;

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

    /** 居民详情:基本信息 + 创造人展示名 + 杰出动态(高光记忆)+ 近期行动 + 配偶/亲密好友 + 产物。 */
    @GetMapping("/agents/{id}/detail")
    public AgentDetail agentDetail(@PathVariable Long id) {
        AgentEmployee e = employeeService.get(id);
        AgentDetail d = new AgentDetail();
        d.setAgent(e);
        d.setCreatorName(resolveOwnerName(e.getCreator()));
        d.setHighlights(memoryRepo.findTop12ByAgentIdOrderByImportanceDescIdDesc(id));
        d.setRecentActions(actionRepo.findTop15ByAgentIdOrderByIdDesc(id));
        d.setProducts(productRepo.findByAgentIdOrderByIdDesc(id));
        if (e.getSpouseId() != null) {
            d.setSpouseName(employeeService.get(e.getSpouseId()).getName());
        }
        // 亲密好友:按亲密度取前若干,附对方名字与关系状态
        List<RelationView> rels = new ArrayList<>();
        for (AgentRelationship r : relationshipService.forAgent(id)) {
            Long otherId = r.getAId().equals(id) ? r.getBId() : r.getAId();
            String name;
            try { name = employeeService.get(otherId).getName(); }
            catch (Exception ignore) { continue; }
            rels.add(new RelationView(otherId, name, r.getIntimacy(), r.getStatus()));
            if (rels.size() >= 8) break;
        }
        d.setRelations(rels);
        return d;
    }

    /** 把 ownerId(u:&lt;id&gt; / g:&lt;uuid&gt; / 系统)解析成人类可读的展示名。 */
    private String resolveOwnerName(String creator) {
        if (creator == null || creator.isBlank()) return "系统";
        if (creator.startsWith("u:")) {
            try {
                Long uid = Long.parseLong(creator.substring(2));
                return userRepo.findById(uid).map(User::getUsername).orElse("用户#" + uid);
            } catch (NumberFormatException ignore) {
                return creator;
            }
        }
        if (creator.startsWith("g:")) return "游客";
        return creator;
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

    // ---- 世界日报(每日结算产物) ----

    /** 最近日报列表(至多 30 天,倒序)。游客不返回 token/花费。 */
    @GetMapping("/reports")
    public List<WorldDailyReport> reports(Principal principal) {
        List<WorldDailyReport> list = reportRepo.findTop30ByOrderBySimDateDesc();
        boolean showCost = principal != null && !principal.guest();
        if (!showCost) list.forEach(WorldController::stripCost);
        return list;
    }

    /** 某日日报详情 + 当日产物列表。游客不返回 token/花费。 */
    @GetMapping("/reports/{date}")
    public ReportDetail report(@PathVariable String date, Principal principal) {
        LocalDate d = LocalDate.parse(date);
        WorldDailyReport r = reportRepo.findBySimDate(d).orElse(null);
        boolean showCost = principal != null && !principal.guest();
        if (r != null && !showCost) stripCost(r);
        ReportDetail out = new ReportDetail();
        out.setReport(r);
        out.setProducts(productRepo.findBySimDateOrderByIdAsc(d));
        return out;
    }

    /** 抹掉日报里的 token/花费字段(游客不可见)。 */
    private static void stripCost(WorldDailyReport r) {
        r.setTotalInputTokens(null);
        r.setTotalOutputTokens(null);
        r.setTotalCostRmb(null);
    }

    /** 抹掉沙盒任务里的 token/花费字段(游客不可见)。 */
    private static void stripSandboxCost(SandboxRun r) {
        if (r == null) return;
        r.setActualInputTokens(null);
        r.setActualOutputTokens(null);
        r.setActualCostRmb(null);
        r.setEstCostRmb(null);
    }

    // ---- 产物媒体(画作图片 / 短片视频) ----

    /** 服务某个产物的媒体文件(image→png / video→mp4)。按 content 里存的相对路径取文件。 */
    @GetMapping("/products/{id}/media")
    public org.springframework.http.ResponseEntity<org.springframework.core.io.Resource> productMedia(@PathVariable Long id) {
        AgentProduct p = productRepo.findById(id).orElse(null);
        if (p == null || p.getContent() == null || p.getContent().isBlank()) {
            return org.springframework.http.ResponseEntity.notFound().build();
        }
        String kind = p.getKind() == null ? "" : p.getKind();
        boolean isVideo = "video".equals(kind);
        boolean isImage = "image".equals(kind);
        if (!isVideo && !isImage) return org.springframework.http.ResponseEntity.notFound().build();
        java.nio.file.Path path = mediaClient.resolveMedia(p.getContent());
        org.springframework.core.io.Resource res = new org.springframework.core.io.FileSystemResource(path);
        if (!res.exists()) return org.springframework.http.ResponseEntity.notFound().build();
        org.springframework.http.MediaType type = isVideo
                ? org.springframework.http.MediaType.parseMediaType("video/mp4")
                : org.springframework.http.MediaType.IMAGE_PNG;
        return org.springframework.http.ResponseEntity.ok()
                .contentType(type)
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"product_" + id + (isVideo ? ".mp4" : ".png") + "\"")
                .body(res);
    }

    // ---- 作品馆(全站作品浏览:画作 / 短片 / 歌曲 / 小说连载) ----

    /**
     * 作品馆按类目取作品(倒序)。category:image(画作,含 image+artwork)/video(短片)/song(歌曲)。
     * 每条附作者名/头像与评论数,供网格卡片展示。
     */
    @GetMapping("/gallery")
    public List<ProductCard> gallery(@RequestParam(defaultValue = "image") String category) {
        List<AgentProduct> list = switch (category) {
            case "video" -> productRepo.findByKindOrderByIdDesc("video");
            case "song" -> productRepo.findByKindOrderByIdDesc("song");
            default -> productRepo.findByKindInOrderByIdDesc(List.of("image", "artwork"));
        };
        List<ProductCard> out = new ArrayList<>();
        for (AgentProduct p : list) out.add(toCard(p));
        return out;
    }

    /** 连载书籍列表:按作者聚合其小说章节,给出书名(取首章标题)、章节数、最新章。 */
    @GetMapping("/books")
    public List<BookCard> books() {
        Map<Long, List<AgentProduct>> byAgent = new LinkedHashMap<>();
        for (AgentProduct p : productRepo.findByKindOrderByIdDesc("chapter")) {
            byAgent.computeIfAbsent(p.getAgentId(), k -> new ArrayList<>()).add(p);
        }
        List<BookCard> out = new ArrayList<>();
        for (Map.Entry<Long, List<AgentProduct>> en : byAgent.entrySet()) {
            List<AgentProduct> chapters = en.getValue();
            chapters.sort(Comparator.comparing(AgentProduct::getSeq,
                    Comparator.nullsLast(Comparator.naturalOrder())));
            AgentProduct first = chapters.get(0);
            AgentProduct last = chapters.get(chapters.size() - 1);
            AgentEmployee author = safeAgent(en.getKey());
            BookCard c = new BookCard();
            c.setAgentId(en.getKey());
            c.setAuthorName(author == null ? "神秘作者" : author.getName());
            c.setAuthorAvatar(author == null ? "📖" : author.getAvatar());
            c.setChapterCount(chapters.size());
            c.setLatestTitle(last.getTitle());
            c.setUpdatedAt(last.getCreatedAt());
            out.add(c);
        }
        out.sort(Comparator.comparing(BookCard::getUpdatedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return out;
    }

    /** 某作者的连载全书:章节目录 + 每章正文(供阅读器翻页)。 */
    @GetMapping("/books/{agentId}")
    public BookDetail book(@PathVariable Long agentId) {
        AgentEmployee author = safeAgent(agentId);
        BookDetail d = new BookDetail();
        d.setAgentId(agentId);
        d.setAuthorName(author == null ? "神秘作者" : author.getName());
        d.setAuthorAvatar(author == null ? "📖" : author.getAvatar());
        d.setChapters(productRepo.findByAgentIdAndKindOrderBySeqAscIdAsc(agentId, "chapter"));
        return d;
    }

    /** 单个作品详情(帖子头):作品本体 + 作者名/头像 + 评论数。 */
    @GetMapping("/products/{id}")
    public ProductCard product(@PathVariable Long id) {
        AgentProduct p = productRepo.findById(id).orElse(null);
        if (p == null) return null;
        return toCard(p);
    }

    private ProductCard toCard(AgentProduct p) {
        AgentEmployee a = safeAgent(p.getAgentId());
        ProductCard c = new ProductCard();
        c.setProduct(p);
        c.setAuthorName(a == null ? "神秘居民" : a.getName());
        c.setAuthorAvatar(a == null ? "🧑‍🎨" : a.getAvatar());
        c.setCommentCount(commentRepo.countByTargetTypeAndTargetId("product", p.getId()));
        return c;
    }

    private AgentEmployee safeAgent(Long id) {
        try { return employeeService.get(id); }
        catch (Exception e) { return null; }
    }

    // ---- 自由评价(对作品 / 居民的评论) ----

    /** 取某对象的评论列表(倒序)。targetType:product / agent。 */
    @GetMapping("/comments")
    public List<AgentComment> comments(@RequestParam String targetType, @RequestParam Long targetId) {
        return commentRepo.findByTargetTypeAndTargetIdOrderByIdDesc(targetType, targetId);
    }

    /** 发表评论。作者由 Principal 解析(用户名 / 游客)。 */
    @PostMapping("/comments")
    public AgentComment addComment(@RequestBody CommentReq req, Principal principal) {
        if (req == null || req.getContent() == null || req.getContent().isBlank()) {
            throw new IllegalArgumentException("评论内容不能为空");
        }
        String type = req.getTargetType();
        if (!"product".equals(type) && !"agent".equals(type)) {
            throw new IllegalArgumentException("非法的评论对象");
        }
        AgentComment c = new AgentComment();
        c.setTargetType(type);
        c.setTargetId(req.getTargetId());
        c.setAuthorId(principal == null ? null : principal.ownerId());
        c.setAuthorName(resolveOwnerName(principal == null ? null : principal.ownerId()));
        c.setContent(req.getContent().trim());
        return commentRepo.save(c);
    }

    /** 删除评论:仅本人或管理员。 */
    @DeleteMapping("/comments/{id}")
    public void deleteComment(@PathVariable Long id, Principal principal) {
        AgentComment c = commentRepo.findById(id).orElse(null);
        if (c == null) return;
        boolean admin = employeeService.isAdmin(principal);
        boolean owner = principal != null && principal.ownerId() != null
                && principal.ownerId().equals(c.getAuthorId());
        if (!admin && !owner) throw new IllegalStateException("无权删除该评论");
        commentRepo.deleteById(id);
    }

    // ---- 世界设置(自主行动总开关/间隔/模型) ----

    @GetMapping("/settings")
    public WorldSettings getSettings() {
        return settingsService.get();
    }

    @PutMapping("/settings")
    public WorldSettings updateSettings(@RequestBody SettingsReq req, Principal principal) {
        return settingsService.update(
                req == null ? null : req.getAutonomousEnabled(),
                req == null ? null : req.getIntervalSeconds(),
                req == null ? null : req.getModel(),
                employeeService.isAdmin(principal));
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
        java.util.Set<String> seen = new java.util.HashSet<>();
        // action 源优先入列并登记指纹;memory 源若与某条 action 同一居民+同一文案则视为重复,跳过
        for (AgentAction a : actionRepo.findTop300ByOrderByIdDesc()) {
            seen.add(dedupKey(a.getAgentId(), a.getContent()));
            out.add(FeedItem.of("action", a.getId(), a.getAgentId(), a.getType(),
                    a.getContent(), a.getTargetAgentId(), a.getPlace(), a.getScene(), a.getCreatedAt()));
        }
        for (AgentMemory mem : memoryRepo.findTop300ByOrderByIdDesc()) {
            if (seen.contains(dedupKey(mem.getAgentId(), mem.getContent()))) continue;
            out.add(FeedItem.of("memory", mem.getId(), mem.getAgentId(), mem.getKind(),
                    mem.getContent(), mem.getRelatedAgentId(), null, null, mem.getCreatedAt()));
        }
        out.sort(Comparator.comparing(FeedItem::getCreatedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return out;
    }

    private static String dedupKey(Long agentId, String content) {
        return agentId + "|" + (content == null ? "" : content.trim());
    }

    // ---- 沙盒快进(隔离模拟,不影响真实世界;每人限一次,管理员不限) ----

    /** 预估某次快进的 LLM 调用/token/花费,并附「本人是否已用过一次」。 */
    @PostMapping("/sandbox/estimate")
    public SandboxEstimateResp sandboxEstimate(@RequestBody SandboxReq req, Principal principal) {
        SandboxService.Estimate est = sandboxService.estimate(
                req == null || req.getYears() == null ? 1 : req.getYears(),
                req == null ? null : req.getMemberIds());
        return new SandboxEstimateResp(est, sandboxService.alreadyUsed(principal));
    }

    /** 发起一次沙盒运行(一次性 gate)。返回创建的任务(异步跑)。 */
    @PostMapping("/sandbox/run")
    public SandboxRun sandboxRun(@RequestBody SandboxReq req, Principal principal) {
        return sandboxService.start(principal,
                req == null ? null : req.getTitle(),
                req == null || req.getYears() == null ? 1 : req.getYears(),
                req == null ? null : req.getMemberIds());
    }

    /** 我的沙盒历史(管理员看全部)。游客不返回 token/花费。 */
    @GetMapping("/sandbox")
    public List<SandboxRun> sandboxList(Principal principal) {
        List<SandboxRun> list = sandboxService.list(principal);
        if (principal == null || principal.guest()) list.forEach(WorldController::stripSandboxCost);
        return list;
    }

    /** 某次沙盒详情(含世界报告)。游客不返回 token/花费。 */
    @GetMapping("/sandbox/{id}")
    public SandboxRun sandboxGet(@PathVariable Long id, Principal principal) {
        SandboxRun r = sandboxService.get(id);
        if (principal == null || principal.guest()) stripSandboxCost(r);
        return r;
    }

    /** 某次沙盒的事件时间线(供回放)。 */
    @GetMapping("/sandbox/{id}/events")
    public List<SandboxEvent> sandboxEvents(@PathVariable Long id) {
        return sandboxService.events(id);
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

    @Data
    public static class SandboxReq {
        private String title;
        private Integer years;
        private List<Long> memberIds;
    }

    /** /sandbox/estimate 响应:预估明细 + 本人是否已用过一次(前端据此置灰入口)。 */
    public record SandboxEstimateResp(SandboxService.Estimate estimate, boolean alreadyUsed) {}

    /** /agents/{id}/detail 响应:居民 + 创造人展示名 + 杰出动态 + 近期行动 + 关系 + 产物。 */
    @Data
    public static class AgentDetail {
        private AgentEmployee agent;
        private String creatorName;
        private String spouseName;
        private List<AgentMemory> highlights;
        private List<AgentAction> recentActions;
        private List<RelationView> relations;
        private List<AgentProduct> products;
    }

    /** 详情页里的一条关系展示:对方 id/名字 + 亲密度 + 关系状态。 */
    public record RelationView(Long agentId, String name, Integer intimacy, String status) {}

    /** /reports/{date} 响应:日报 + 当日产物。 */
    @Data
    public static class ReportDetail {
        private WorldDailyReport report;
        private List<AgentProduct> products;
    }

    /** 作品馆卡片 / 帖子头:作品本体 + 作者展示信息 + 评论数。 */
    @Data
    public static class ProductCard {
        private AgentProduct product;
        private String authorName;
        private String authorAvatar;
        private long commentCount;
    }

    /** 连载书籍卡片:作者 + 章节数 + 最新章。 */
    @Data
    public static class BookCard {
        private Long agentId;
        private String authorName;
        private String authorAvatar;
        private int chapterCount;
        private String latestTitle;
        private LocalDateTime updatedAt;
    }

    /** 连载全书:作者信息 + 章节(含正文,供阅读器翻页)。 */
    @Data
    public static class BookDetail {
        private Long agentId;
        private String authorName;
        private String authorAvatar;
        private List<AgentProduct> chapters;
    }

    /** 发表评论请求体。 */
    @Data
    public static class CommentReq {
        private String targetType;   // product / agent
        private Long targetId;
        private String content;
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
