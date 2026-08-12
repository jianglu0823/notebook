package io.llmnote.world.forum;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.llmnote.llm.ChatModelFactory;
import io.llmnote.world.AgentEmployee;
import io.llmnote.world.AgentEmployeeRepository;
import io.llmnote.world.WorldSettings;
import io.llmnote.world.WorldSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 论坛每日定时生成:逐 agent 一次 LLM,返回 1~3 条帖子 JSON 数组,落 agent_forum_post。 */
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

    /** cron 触发:逐个 active agent 调 LLM,生成 1~3 条帖子。 */
    @Scheduled(cron = "${forum.cron:0 0 21 * * *}")
    public void generateDailyPosts() {
        WorldSettings s = settingsService.get();
        if (!s.getForumEnabled()) {
            log.info("[forum-paused] skip daily generation (WorldSettings.forumEnabled=false)");
            return;
        }
        int maxPerAgent = s.getForumMaxPostsPerAgentPerDay() == null ? 2 : s.getForumMaxPostsPerAgentPerDay();
        List<AgentEmployee> agents = employeeRepo.findByStatusOrderByIdAsc("active");
        log.info("[forum] daily generation start agents={} maxPerAgent={}", agents.size(), maxPerAgent);
        for (AgentEmployee e : agents) {
            try {
                generateForAgent(e, maxPerAgent);
            } catch (Exception ex) {
                log.warn("[forum] agent={} generation failed: {}", e.getId(), ex.getMessage());
            }
        }
    }

    /** 单 agent:取近 7 天类目分布 → prompt → LLM → parse → save。 */
    void generateForAgent(AgentEmployee e, int maxPerAgent) {
        Map<String, Long> recent7d = recentCategoryStats(e.getId());
        var prompt = buildPrompt(e, recent7d, maxPerAgent);
        String raw = callLlm(prompt.system(), prompt.user());
        if (raw == null || raw.isBlank()) {
            log.warn("[forum] agent={} LLM empty response", e.getId());
            return;
        }
        List<AgentForumPost> posts = parseResponse(raw, e, LocalDate.now());
        for (AgentForumPost p : posts) {
            postRepo.save(p);
        }
        if (!posts.isEmpty()) {
            log.info("[forum] agent={} generated {} posts", e.getId(), posts.size());
        }
    }

    /** 取 agent 近 7 天类目统计,避免单一类目刷屏。 */
    Map<String, Long> recentCategoryStats(Long agentId) {
        LocalDateTime since = LocalDateTime.now().minusDays(7);
        return postRepo.findByAuthorAgentIdOrderByCreatedAtDesc(agentId).stream()
                .filter(p -> p.getCreatedAt() != null && p.getCreatedAt().isAfter(since))
                .collect(Collectors.groupingBy(AgentForumPost::getCategory, Collectors.counting()));
    }

    /**
     * 构建 system + user prompt(system 强约束 c5 三段 + 6 类目 + 套话黑名单;user 携带 agent 上下文)。
     * 返回 record 让 caller 用 (system, user) 调 LLM。
     */
    PromptPair buildPrompt(AgentEmployee e, Map<String, Long> recent7d, int maxPerAgent) {
        String persona = e.getPersona() == null ? "" : e.getPersona();
        String system = """
                你是 %s,你的职业和性格设定见「人设」。
                人设:%s

                【个性】你不是一个无情的输出机器。你发这条帖子,基于你作为 %s 的真实生活/想法/情绪,不是流水账。
                【内涵】段子/藏头诗/鸡汤要让人会心一笑或若有所思 —— 可以用双关、典故、反差、自嘲、谐音梗、藏头诗(每行首字组成关键词)。"内涵"不是堆砌辞藻,而是"看完想点赞"或"看完想截图"。
                【自主性】不要每次都"今天天气真好/工作辛苦/加油"。你要有自己的观点、自己的小确幸或小牢骚。"鸡汤"也可以是你作为过来人的反鸡汤。

                类目清单(必须从中选,1~%d 条):joke / acrostic / daily / chicken_soup / video_series / utility
                - joke:段子,正文 30~300 字符
                - acrostic:藏头诗,4 行,每行首字组成关键词(关键词由你定),标题必填
                - daily:日常分享,正文 30~300 字符
                - chicken_soup:心灵鸡汤/反鸡汤,正文 30~300 字符
                - video_series:视频连载剧情简介,正文 50~500 字符,标题必填
                - utility:实用工具(如情侣头像),标题必填,正文 30~300 字符

                套话黑名单(以下开头 MUST 重新生成,直到不含为止):%s

                你近 7 天发过的类目:%s —— 今天请优先选你没发过或发得少的类目。

                严格返回 JSON 数组(无前后说明文字):
                [{"category":"joke","title":null,"content":"..."},{"category":"acrostic","title":"代码人生","content":"代...\\n码...\\n人...\\n生..."}]
                """.formatted(
                e.getName(), persona, e.getName(), maxPerAgent,
                String.join(" / ", CLICHE_PREFIXES),
                recent7d.isEmpty() ? "(无)" : recent7d.toString());
        String user = "今天 %s 想说点啥?请生成 JSON 数组(1~%d 条)。".formatted(e.getName(), maxPerAgent);
        return new PromptPair(system, user);
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
    String callLlm(String system, String user) {
        try {
            List<Msg> messages = List.of(
                    Msg.builder().role(MsgRole.SYSTEM).content(TextBlock.builder().text(system).build()).build(),
                    Msg.builder().role(MsgRole.USER).content(TextBlock.builder().text(user).build()).build());
            List<ChatResponse> responses = modelFactory.streamTextWithFallback(modelFactory.defaultTextModel(), messages, (GenerateOptions) null);
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
