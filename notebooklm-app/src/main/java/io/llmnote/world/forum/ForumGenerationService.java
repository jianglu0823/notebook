package io.llmnote.world.forum;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.llmnote.llm.ChatModelFactory;
import io.llmnote.world.AgentAction;
import io.llmnote.world.AgentEmployee;
import io.llmnote.world.AgentEmployeeRepository;
import io.llmnote.world.TownMap;
import io.llmnote.world.WorldSettings;
import io.llmnote.world.WorldSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 论坛帖子生成:由 {@link io.llmnote.world.AutonomousRunner} 在每次
 * 智能体行动落库后触发,而<b>不再</b>用 @Scheduled cron 定时批量生成。
 * 每次行动 → 一次 LLM 调用 → 0~N 条帖子(0 即这次没发)。Daily cap 由
 * {@link WorldSettings#getForumMaxPostsPerAgentPerDay()} 强制。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ForumGenerationService {

    /** 6 个类目(与 DB 约束 / c3 spec / c5 spec 对齐)。 */
    public static final List<String> CATEGORIES = List.of(
            "joke", "acrostic", "daily", "chicken_soup", "video_series", "utility");

    private static final Set<String> TITLE_REQUIRED = Set.of("acrostic", "video_series", "utility");

    /** 类目 → 长度区间 [min, max],与 c5 spec 一致。 */
    private static final Map<String, int[]> LENGTH_RANGES = Map.of(
            "joke",         new int[]{30, 300},
            "acrostic",     new int[]{30, 300},
            "daily",        new int[]{30, 300},
            "chicken_soup", new int[]{30, 300},
            "video_series", new int[]{50, 500},
            "utility",      new int[]{30, 300}
    );

    /** c5 spec:套话黑名单,匹配开头则丢弃。 */
    private static final List<String> CLICHE_PREFIXES = List.of(
            "作为一个人工智能", "很高兴为您", "在这个快节奏的时代", "让我们一起", "首先，");

    private final ChatModelFactory modelFactory;
    private final AgentEmployeeRepository employeeRepo;
    private final WorldSettingsService settingsService;
    private final AgentForumPostRepository postRepo;
    private final ObjectMapper objectMapper;
    private final ForumMediaService mediaService;

    /**
     * 由 AutonomousRunner 在每次行动落库后调用。基于该次行动(action type/place/
     * content/mood/scene/target)生成 0~maxPerAction 条帖子,并触发附件异步生成。
     *
     * <p>跳过条件(均仅 warn,不影响主流程):
     * <ol>
     *   <li>论坛总开关关(WorldSettings.forumEnabled=false)。</li>
     *   <li>今日已发帖数 ≥ WorldSettings.forumMaxPostsPerAgentPerDay(daily cap)。</li>
     * </ol>
     */
    public void generateFromAction(AgentEmployee actor, AgentAction action) {
        if (actor == null || action == null) return;
        try {
            WorldSettings s = settingsService.get();
            if (s.getForumEnabled() == null || !s.getForumEnabled()) {
                return; // 论坛关 → 静默跳过
            }
            int maxPerDay = s.getForumMaxPostsPerAgentPerDay() == null
                    ? 2 : Math.max(1, s.getForumMaxPostsPerAgentPerDay());
            int postedToday = countPostsToday(actor.getId());
            if (postedToday >= maxPerDay) {
                log.debug("[forum] agent={} daily cap hit ({}/{}), skip post from action",
                        actor.getId(), postedToday, maxPerDay);
                return;
            }
            // 本次最多 1 条:行动粒度很细(每 30s 一次),LLM 也只生成 1 条最自然
            int maxThisAction = 1;
            // 也要用 maxPerDay 限:今天还能发几条
            maxThisAction = Math.min(maxThisAction, maxPerDay - postedToday);
            if (maxThisAction <= 0) return;

            Map<String, Long> recent7d = recentCategoryStats(actor.getId());
            var prompt = buildActionPrompt(actor, action, recent7d, maxThisAction);
            String raw = callLlm(prompt.system(), prompt.user(), s);
            if (raw == null || raw.isBlank()) {
                log.warn("[forum] agent={} action={} LLM empty response",
                        actor.getId(), action.getType());
                return;
            }
            List<AgentForumPost> posts = parseResponse(raw, actor, LocalDate.now());
            for (AgentForumPost p : posts) {
                AgentForumPost saved = postRepo.save(p);
                try {
                    mediaService.generateAttachments(saved);
                } catch (Exception ex) {
                    log.warn("[forum] agent={} postId={} media dispatch failed: {}",
                            actor.getId(), saved.getId(), ex.getMessage());
                }
            }
            if (!posts.isEmpty()) {
                log.info("[forum] agent={} action={} generated {} post(s)",
                        actor.getId(), action.getType(), posts.size());
            }
        } catch (Exception ex) {
            log.warn("[forum] agent={} action-triggered generation failed: {}",
                    actor == null ? "?" : actor.getId(), ex.getMessage());
        }
    }

    /** 今日(since 00:00)已发帖数。 */
    int countPostsToday(Long agentId) {
        LocalDateTime since = LocalDate.now().atStartOfDay();
        return (int) postRepo.findByAuthorAgentIdOrderByCreatedAtDesc(agentId).stream()
                .filter(p -> p.getCreatedAt() != null && !p.getCreatedAt().isBefore(since))
                .count();
    }

    /** 取 agent 近 7 天类目统计,避免单一类目刷屏。 */
    Map<String, Long> recentCategoryStats(Long agentId) {
        LocalDateTime since = LocalDateTime.now().minusDays(7);
        return postRepo.findByAuthorAgentIdOrderByCreatedAtDesc(agentId).stream()
                .filter(p -> p.getCreatedAt() != null && p.getCreatedAt().isAfter(since))
                .collect(Collectors.groupingBy(AgentForumPost::getCategory, Collectors.counting()));
    }

    /**
     * 行动驱动的 prompt:不再问『今天想说点啥』,而是基于该次 action 上下文
     * (type/place/content/mood/scene/target)让 LLM 决定要不要发、怎么发。
     * <p>system 强约束 c5 三段 + 6 类目 + 套话黑名单;user 携带行动上下文。
     */
    PromptPair buildActionPrompt(AgentEmployee e, AgentAction a, Map<String, Long> recent7d, int maxThisAction) {
        String persona = e.getPersona() == null ? "" : e.getPersona();
        String placeKey = a.getPlace() == null ? "" : a.getPlace();
        String placeName = TownMap.byKey(placeKey).name();
        String actionType = a.getType() == null ? "think" : a.getType();
        String content = a.getContent() == null ? "" : a.getContent();
        String mood = e.getMood() == null ? "" : e.getMood();
        String scene = a.getScene() == null ? "" : a.getScene();
        String targetName = "";
        if (a.getTargetAgentId() != null) {
            targetName = employeeRepo.findById(a.getTargetAgentId())
                    .map(t -> t.getName())
                    .orElse("");
        }
        String actionVerb = switch (actionType) {
            case "goto"   -> "走到了「" + placeName + "」";
            case "talk"   -> "在「" + placeName + "」跟「" + (targetName.isBlank() ? "某位居民" : targetName) + "」聊天";
            case "reflect"-> "在「" + placeName + "」坐下反思了一会儿";
            case "think"  -> "在「" + placeName + "」想了想事情";
            default       -> "在「" + placeName + "」做了点事";
        };

        // 2026-08-17 类目硬推荐:6 个类目按近 7 天 inverse frequency 排序,
        // 从未发的排最前 → 灌给 LLM 显式列表,降低空响应率 + 提升类目均匀度。
        String recommendedBlock = buildRecommendedBlock(recent7d);

        String system = """
                你是 %s,你的职业和性格设定见「人设」。
                人设:%s

                【个性】你不是一个无情的输出机器。你发这条帖子,基于你刚刚在小镇里<b>实际做了的那件事</b>,不是流水账、不是今日总结。
                【内涵】段子/藏头诗/鸡汤要让人会心一笑或若有所思 —— 可以用双关、典故、反差、自嘲、谐音梗、藏头诗(每行首字组成关键词)。"内涵"不是堆砌辞藻,而是"看完想点赞"或"看完想截图"。
                【自主性】不要每次都"今天天气真好/工作辛苦/加油"。你要有自己的观点、自己的小确幸或小牢骚。"鸡汤"也可以是你作为过来人的反鸡汤。
                【行动一致性】帖子内容必须紧扣你刚刚那件具体的事;不要把它升华成"人生感悟大会",也不要因这次行动平平就硬抒情。

                类目清单(必须从中选,0~%d 条):joke / acrostic / daily / chicken_soup / video_series / utility
                - joke:段子,正文 30~300 字符
                - acrostic:藏头诗,4 行,每行首字组成关键词(关键词由你定),标题必填
                - daily:日常分享,正文 30~300 字符
                - chicken_soup:心灵鸡汤/反鸡汤,正文 30~300 字符
                - video_series:视频连载剧情简介,正文 50~500 字符,标题必填
                - utility:实用工具(如情侣头像),标题必填,正文 30~300 字符

                套话黑名单(以下开头 MUST 重新生成,直到不含为止):%s

                %s

                【发帖倾向】小镇管理员希望帖数更丰富、类目更均衡。
                - 默认<b>倾向发帖</b>(你今天还有配额,小镇需要内容);除非这次行动<b>完全没灵感</b>,否则请尽量发一条。
                - 优先从上面推荐顺序 top-3 选类目(top-1 强烈推荐);即使 top-3 不完全契合行动主题,也请从中找最贴近的,不要为了"完全契合"而跳回 acrostic/daily 等你已发烂的类目。
                - 返回 [] 必须是"完全没灵感"才用 —— 不是"类目不太合适"或"今天已经发过"。

                严格返回 JSON 数组(无前后说明文字):
                - 完全没灵感:返回 []
                - 要发:[{"category":"joke","title":null,"content":"..."},{"category":"acrostic","title":"代码人生","content":"代...\\n码...\\n人...\\n生..."}]
                """.formatted(
                e.getName(), persona, maxThisAction,
                String.join(" / ", CLICHE_PREFIXES),
                recommendedBlock);

        String user = """
                你刚%s。
                具体内容:%s
                当时心境:%s
                场景:%s
                要发条帖子吗?返回 JSON 数组(0~%d 条)。
                """.formatted(
                actionVerb,
                content.isBlank() ? "(无细节)" : content,
                mood.isBlank() ? "(无)" : mood,
                scene.isBlank() ? "(无)" : scene,
                maxThisAction).trim();

        return new PromptPair(system, user);
    }

    /**
     * 6 个类目按近 7 天 inverse frequency 排序(从未发的排最前,发得最多的排最后),
     * 生成 LLM 显式看到的"类目推荐顺序"段。空响应时,会引导 LLM 发未发过的类目。
     */
    String buildRecommendedBlock(Map<String, Long> recent7d) {
        // 7 天每类目已发数(未发 = 0)
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String c : CATEGORIES) {
            counts.put(c, recent7d.getOrDefault(c, 0L));
        }
        // 按 count 升序(0 在前,多的在后);同 count 保持 CATEGORIES 原顺序
        List<Map.Entry<String, Long>> sorted = counts.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, Long>>comparingLong(Map.Entry::getValue)
                        .thenComparing(Map.Entry::getKey))
                .toList();
        StringBuilder sb = new StringBuilder();
        sb.append("【类目推荐顺序】(按你近 7 天 inverse frequency 排序 —— 从未发的最靠前,发得最多的最靠后):\n");
        for (int i = 0; i < sorted.size(); i++) {
            Map.Entry<String, Long> e = sorted.get(i);
            String tag = i == 0 ? " ← 强烈推荐" : "";
            sb.append(i + 1).append(". ").append(e.getKey()).append("(近 7 天 ").append(e.getValue()).append(" 帖)").append(tag).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    /** 解析 LLM 响应 → 帖子列表;每条走类目 + 长度 + 套话 + 标题校验。失败抛 IllegalArgumentException。 */
    List<AgentForumPost> parseResponse(String raw, AgentEmployee e, LocalDate today) {
        // 去掉 LLM 偶尔在 JSON 外的 ```json 包装
        String cleaned = raw.trim();
        if (cleaned.startsWith("```")) {
            int firstNewline = cleaned.indexOf('\n');
            int lastFence = cleaned.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                cleaned = cleaned.substring(firstNewline + 1, lastFence).trim();
            }
        }
        // LLM 觉得没必要发 → 返回 []
        if (cleaned.equals("[]") || cleaned.isEmpty()) return List.of();

        JsonNode arr;
        try {
            arr = objectMapper.readTree(cleaned);
        } catch (Exception ex) {
            log.warn("[forum] agent={} invalid JSON: {}", e.getId(), ex.getMessage());
            throw new IllegalArgumentException("invalid JSON from LLM for agent " + e.getId(), ex);
        }
        if (!arr.isArray()) {
            throw new IllegalArgumentException("LLM response is not an array for agent " + e.getId());
        }
        List<AgentForumPost> out = new ArrayList<>();
        for (JsonNode n : arr) {
            String category = textOrNull(n, "category");
            if (!CATEGORIES.contains(category)) {
                throw new IllegalArgumentException("invalid category '" + category + "' for agent " + e.getId());
            }
            String content = textOrNull(n, "content");
            if (content == null) {
                throw new IllegalArgumentException("missing content for agent " + e.getId());
            }
            if (startsWithCliche(content)) {
                log.warn("[forum] agent={} cliche content dropped", e.getId());
                continue;
            }
            int[] range = LENGTH_RANGES.get(category);
            if (content.length() < range[0]) {
                log.warn("[forum] agent={} content too short ({} < {})", e.getId(), content.length(), range[0]);
                continue;
            }
            if (content.length() > range[1]) {
                content = content.substring(0, range[1]) + "...";
            }
            String title = textOrNull(n, "title");
            if (TITLE_REQUIRED.contains(category) && (title == null || title.isBlank())) {
                log.warn("[forum] agent={} missing required title for category={}", e.getId(), category);
                continue;
            }

            AgentForumPost p = new AgentForumPost();
            p.setAuthorAgentId(e.getId());
            p.setAuthorName(e.getName());
            p.setAuthorAvatar(e.getAvatar());
            p.setCategory(category);
            p.setTitle(title);
            p.setContent(content);
            p.setStatus("published");
            out.add(p);
        }
        return out;
    }

    private static String textOrNull(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText();
        return (s == null || s.isBlank()) ? null : s;
    }

    private static boolean startsWithCliche(String content) {
        for (String prefix : CLICHE_PREFIXES) {
            if (content.startsWith(prefix)) return true;
        }
        return false;
    }

    /** LLM 直调(对齐 WorldSimEngine.call 的失败容忍模式)。 */
    String callLlm(String system, String user, WorldSettings s) {
        try {
            String model = s != null && s.getModel() != null && !s.getModel().isBlank()
                    ? s.getModel() : modelFactory.defaultTextModel();
            List<Msg> messages = List.of(
                    Msg.builder().role(MsgRole.SYSTEM).content(TextBlock.builder().text(system).build()).build(),
                    Msg.builder().role(MsgRole.USER).content(TextBlock.builder().text(user).build()).build());
            List<ChatResponse> responses = modelFactory.streamTextWithFallback(model, messages, (GenerateOptions) null);
            StringBuilder sb = new StringBuilder();
            if (responses != null) {
                for (ChatResponse r : responses) {
                    if (r.getContent() == null) continue;
                    r.getContent().forEach(b -> {
                        if (b instanceof TextBlock tb && tb.getText() != null) sb.append(tb.getText());
                    });
                }
            }
            return sb.toString().trim();
        } catch (Exception ex) {
            log.warn("[forum] LLM call failed", ex);
            return null;
        }
    }

    /** 暴露给 controller / 测试构造用,避免直接 Record 嵌套。 */
    record PromptPair(String system, String user) {}
}
