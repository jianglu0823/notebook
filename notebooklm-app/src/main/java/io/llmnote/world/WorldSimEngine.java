package io.llmnote.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;

import io.llmnote.llm.ChatModelFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 活世界「每日结算」引擎:跨过午夜触发一次(见 {@link AutonomousRunner})。
 * 负责:季节/天气 → 职业产物(作家连载小说/歌手新歌,qwen-turbo 单次生成)→ 经济发放
 * → 汇总当日全世界 token/花费 → qwen-turbo 写一段当日小镇日报叙事,落 {@link WorldDailyReport}。
 *
 * <p>token/花费仅拼给管理者看,<b>不进任何居民 prompt</b>。关系/婚育/死亡由 Phase C 接入。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorldSimEngine {

    static final String[] SEASONS = {"冬", "春", "夏", "秋"};
    // 各季节可选天气(按季节风味)
    private static final Map<String, String[]> SEASON_WEATHER = Map.of(
            "春", new String[]{"晴", "小雨", "微风", "多云", "花粉飞舞"},
            "夏", new String[]{"烈日", "雷阵雨", "闷热", "晴", "台风"},
            "秋", new String[]{"晴", "秋高气爽", "阴", "小雨", "落叶"},
            "冬", new String[]{"晴", "小雪", "寒风", "阴", "大雪"});

    /** 季节温度基准(℃):随机游走向其回归。 */
    private static final Map<String, Integer> SEASON_TEMP_BASE = Map.of("冬", 0, "春", 15, "夏", 30, "秋", 17);
    /** 季节温度区间下限(℃)。 */
    private static final Map<String, Integer> SEASON_TEMP_MIN = Map.of("冬", -10, "春", 5, "夏", 22, "秋", 8);
    /** 季节温度区间上限(℃)。 */
    private static final Map<String, Integer> SEASON_TEMP_MAX = Map.of("冬", 10, "春", 25, "夏", 38, "秋", 26);

    /** 天气档位:好=晴朗宜人,极端=灾害性;其余按 BAD/中处理。用于马尔可夫式状况选取与天气效应。 */
    private static final Set<String> WEATHER_GOOD = Set.of("晴", "秋高气爽", "微风");
    private static final Set<String> WEATHER_EXTREME = Set.of("台风", "大雪", "雷阵雨");
    private static final Set<String> WEATHER_BAD = Set.of("小雨", "寒风", "花粉飞舞", "烈日", "闷热");

    /** 轻量天气状态值对象:状况 + 温度(℃)。 */
    record WeatherState(String condition, int temp) {}

    /** 天气对当日结算的效应(加性概率增量,正=更易触发;extreme=灾害天气)。 */
    record WeatherEffect(int outdoorDelta, int indoorDelta, int weddingDelta, boolean extreme) {}

    /** 天气档位分类:0=好 1=中 2=差 3=极端。 */
    private static int weatherTier(String condition) {
        if (condition == null) return 1;
        if (WEATHER_EXTREME.contains(condition)) return 3;
        if (WEATHER_GOOD.contains(condition)) return 0;
        if (WEATHER_BAD.contains(condition)) return 2;
        return 1;
    }

    private final AgentEmployeeRepository employeeRepo;
    private final AgentProductRepository productRepo;
    private final AgentTransactionRepository txRepo;
    private final WorldDailyReportRepository reportRepo;
    private final AgentMemoryService memoryService;
    private final ChatModelFactory modelFactory;
    private final ObjectMapper objectMapper;
    private final AgentRelationshipRepository relationshipRepo;
    private final AgentEmployeeService employeeService;
    private final io.llmnote.llm.ZhipuMediaClient mediaClient;
    private final WorldMediaRunner mediaRunner;
    private final AgentCommentRepository commentRepo;

    /** 由日期求季节(北半球:3-5春 6-8夏 9-11秋 12-2冬)。 */
    public static String seasonOf(LocalDate d) {
        return SEASONS[(d.getMonthValue() % 12) / 3];
    }

    /**
     * 逐日演变天气:温度在昨日基础上小幅漂移(≤±5)并向季节基准回归,钳制到季节区间;
     * 状况按昨日档位做马尔可夫式加权抽取。prev==null 为冷启动(温度=季节基准,状况随机)。
     */
    WeatherState nextWeather(String season, WeatherState prev) {
        int base = SEASON_TEMP_BASE.getOrDefault(season, 15);
        int min = SEASON_TEMP_MIN.getOrDefault(season, -20);
        int max = SEASON_TEMP_MAX.getOrDefault(season, 45);
        String[] pool = SEASON_WEATHER.getOrDefault(season, new String[]{"晴"});

        int temp;
        String condition;
        if (prev == null) {
            temp = base;
            condition = pool[rnd(pool.length)];
        } else {
            int drift = rnd(11) - 5;                 // [-5,5]
            int regression = (base - prev.temp()) / 4; // 向季节基准回归
            temp = clamp(prev.temp() + drift + regression, min, max);
            condition = pickCondition(pool, weatherTier(prev.condition()));
        }
        return new WeatherState(condition, temp);
    }

    /**
     * 按昨日档位对季节状况集合加权抽取:倾向停留在相近档位(晴后倾向晴/多云,雨后倾向阴/雨)。
     * 权重 = 与昨日档位距离越近越高。
     */
    private String pickCondition(String[] pool, int prevTier) {
        List<String> weighted = new ArrayList<>();
        for (String c : pool) {
            int dist = Math.abs(weatherTier(c) - prevTier);
            int w = switch (dist) { case 0 -> 4; case 1 -> 2; default -> 1; };
            for (int i = 0; i < w; i++) weighted.add(c);
        }
        return weighted.isEmpty() ? pool[0] : weighted.get(rnd(weighted.size()));
    }

    /** 天气效应:极端压户外/升室内/降婚礼;好升户外;差略压户外升室内;中性为 0。delta 有界。 */
    WeatherEffect weatherEffect(WeatherState w) {
        int tier = w == null ? 1 : weatherTier(w.condition());
        return switch (tier) {
            case 3 -> new WeatherEffect(-15, 8, 20, true);   // 极端:婚礼阈值 +20(成婚率降)
            case 0 -> new WeatherEffect(8, 0, 0, false);     // 好
            case 2 -> new WeatherEffect(-8, 4, 0, false);    // 差
            default -> new WeatherEffect(0, 0, 0, false);    // 中
        };
    }

    /** 取给定日期的上一日天气状态(状况+温度),供逐日演变;无历史或旧数据无温度则返回 null。 */
    private WeatherState loadPrevWeather(LocalDate date) {
        WorldDailyReport prev = reportRepo.findFirstBySimDateLessThanOrderBySimDateDesc(date).orElse(null);
        if (prev == null || prev.getWeather() == null || prev.getTemperature() == null) return null;
        return new WeatherState(prev.getWeather(), prev.getTemperature());
    }

    /** 概率钳制到 [lo,hi]。 */
    private static int clampProb(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    /**
     * 结算某个内在日期。已有当日日报则跳过(幂等)。返回生成的日报(或 null)。
     */
    public WorldDailyReport dailySettlement(LocalDate date) {
        if (date == null || reportRepo.existsBySimDate(date)) return null;

        long[] tok = {0L, 0L};
        String season = seasonOf(date);
        WeatherState ws = nextWeather(season, loadPrevWeather(date));
        String weather = ws.condition();
        Integer temperature = ws.temp();
        WeatherEffect eff = weatherEffect(ws);

        List<AgentEmployee> active = employeeRepo.findByStatusOrderByIdAsc("active");

        List<Map<String, Object>> highlights = new ArrayList<>();
        int chapters = 0, songs = 0, artworks = 0, films = 0, learns = 0;

        for (AgentEmployee e : active) {
            // 工资/岗位收入(按职业风味给点钱,不再由职业决定产物)
            payWages(e, date, wageReason(e.getOccupation()), 60 + rnd(120));

            // 每日自学:升级已有技能 / 偶尔习得新技能(所有居民都能创作)
            com.fasterxml.jackson.databind.node.ObjectNode skills = ensureSkills(e);
            learns += learnDaily(e, skills);

            // 技能驱动的创作事件:当日至多产出一件作品(按技能等级加权抽取)
            AgentProduct p = maybeCreate(e, date, skills, tok, eff);
            if (p != null) {
                switch (p.getKind()) {
                    case "chapter" -> { chapters++; addHighlight(highlights, e, "连载小说", p.getTitle()); }
                    case "song" -> { songs++; addHighlight(highlights, e, "新歌", p.getTitle()); }
                    case "image", "artwork" -> { artworks++; addHighlight(highlights, e, "画作", p.getTitle()); }
                    case "video" -> { films++; addHighlight(highlights, e, "短片", p.getTitle()); }
                    default -> { }
                }
            }
        }

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("chapters", chapters);
        stats.put("songs", songs);
        stats.put("artworks", artworks);
        stats.put("films", films);
        stats.put("learns", learns);
        stats.put("residents", active.size());

        // ---- Phase C:关系推进(婚礼)/ 生子 / 死亡 / 随机突发事件 ----
        List<Map<String, Object>> news = new ArrayList<>();
        int marriages = weddings(active, date, news, highlights, eff);
        int births = childbirths(active, date, news, highlights);
        int deaths = deaths(active, date, news, highlights);
        randomEvents(active, date, news, ws, eff);
        stats.put("marriages", marriages);
        stats.put("births", births);
        stats.put("deaths", deaths);

        // ---- Phase D:村民互评(每人 50% 几率评论近期作品,互相讨论)----
        int comments = villagerComments(active, date, tok);
        stats.put("comments", comments);

        String narrative = writeNarrative(date, season, weather, temperature, highlights, stats, news, tok);

        WorldDailyReport r = new WorldDailyReport();
        r.setSimDate(date);
        r.setSeason(season);
        r.setWeather(weather);
        r.setTemperature(temperature);
        r.setHighlightsJson(toJson(highlights));
        r.setStatsJson(toJson(stats));
        r.setNewsJson(toJson(news));
        r.setNarrative(narrative);
        r.setTotalInputTokens(tok[0]);
        r.setTotalOutputTokens(tok[1]);
        r.setTotalCostRmb(BigDecimal.valueOf(modelFactory.costRmb(modelFactory.defaultTextModel(), tok[0], tok[1])));
        WorldDailyReport saved = reportRepo.save(r);
        log.info("dailySettlement date={} season={} weather={} temp={} chapters={} songs={} artworks={} films={} learns={} marriages={} births={} deaths={} tokens={}/{}",
                date, season, weather, temperature, chapters, songs, artworks, films, learns, marriages, births, deaths, tok[0], tok[1]);
        return saved;
    }

    // ---- 创作技能:每日自学 + 技能驱动创作 ----

    /** 四种创作技能 key。music 产歌词(song),novel 产小说(chapter),image 产画作,video 产短片。 */
    static final String[] SKILL_KEYS = {"novel", "image", "video", "music"};

    static boolean isCreativeSkill(String k) {
        return "novel".equals(k) || "image".equals(k) || "video".equals(k) || "music".equals(k);
    }

    static String skillLabel(String k) {
        return switch (k) {
            case "novel" -> "写作"; case "image" -> "绘画"; case "video" -> "影像"; case "music" -> "音乐"; default -> k;
        };
    }

    /** 各技能的缺省风格提示词(新居民未指定时的兜底)。 */
    static String defaultStyle(String k) {
        return switch (k) {
            case "novel" -> "温情写实的小镇故事";
            case "image" -> "清新治愈的水彩风";
            case "video" -> "生活流纪实短片";
            case "music" -> "温暖治愈的民谣";
            default -> "";
        };
    }

    private static String skillForOccupation(String occ) {
        if (occ == null) return null;
        return switch (occ) {
            case "writer" -> "novel"; case "singer" -> "music"; case "painter" -> "image"; case "director" -> "video";
            default -> null;
        };
    }

    private static String skillOfKind(String kind) {
        return switch (kind) {
            case "chapter" -> "novel"; case "song" -> "music"; case "image", "artwork" -> "image"; case "video" -> "video";
            default -> kind;
        };
    }

    private static String wageReason(String occ) {
        if (occ == null) return "岗位收入";
        return switch (occ) {
            case "writer" -> "稿费"; case "singer" -> "演出收入"; case "painter" -> "卖画收入"; case "director" -> "创作收入";
            default -> "岗位收入";
        };
    }

    /**
     * 取得(必要时初始化)居民的技能 JSON。空则按职业种一门起手技能(无职业则按 id 分配一门),
     * 熟练度 3 级,并落库。返回可变的 {@link ObjectNode}。
     */
    private com.fasterxml.jackson.databind.node.ObjectNode ensureSkills(AgentEmployee e) {
        com.fasterxml.jackson.databind.node.ObjectNode node = null;
        String raw = e.getSkillsJson();
        if (raw != null && !raw.isBlank()) {
            try {
                com.fasterxml.jackson.databind.JsonNode n = objectMapper.readTree(raw);
                if (n.isObject()) node = (com.fasterxml.jackson.databind.node.ObjectNode) n;
            } catch (Exception ignore) { }
        }
        if (node == null || node.size() == 0) {
            node = objectMapper.createObjectNode();
            String seed = skillForOccupation(e.getOccupation());
            if (seed == null) seed = SKILL_KEYS[(int) (Math.abs(e.getId()) % SKILL_KEYS.length)];
            com.fasterxml.jackson.databind.node.ObjectNode s = objectMapper.createObjectNode();
            s.put("lv", 3);
            s.put("style", defaultStyle(seed));
            node.set(seed, s);
            e.setSkillsJson(node.toString());
            employeeRepo.save(e);
        }
        return node;
    }

    /**
     * 每日自学:40% 精进一门已有技能(+1 级,上限 10),8% 习得一门新技能(至多 4 门)。
     * 变更落库并写进个人记忆。返回今日学习事件数。
     */
    private int learnDaily(AgentEmployee e, com.fasterxml.jackson.databind.node.ObjectNode skills) {
        int events = 0;
        List<String> owned = new ArrayList<>();
        skills.fieldNames().forEachRemaining(owned::add);
        if (!owned.isEmpty() && rnd(100) < 40) {
            String k = owned.get(rnd(owned.size()));
            com.fasterxml.jackson.databind.node.ObjectNode s = (com.fasterxml.jackson.databind.node.ObjectNode) skills.get(k);
            int lv = s.path("lv").asInt(1);
            if (lv < 10) {
                s.put("lv", lv + 1);
                events++;
                memoryService.record(e.getId(), "reflection", "我的" + skillLabel(k) + "技艺精进到了 " + (lv + 1) + " 级。", 5, null);
            }
        }
        if (owned.size() < SKILL_KEYS.length && rnd(100) < 8) {
            List<String> shuffled = new ArrayList<>(List.of(SKILL_KEYS));
            java.util.Collections.shuffle(shuffled);
            for (String k : shuffled) {
                if (!skills.has(k)) {
                    com.fasterxml.jackson.databind.node.ObjectNode s = objectMapper.createObjectNode();
                    s.put("lv", 1);
                    s.put("style", defaultStyle(k));
                    skills.set(k, s);
                    events++;
                    memoryService.record(e.getId(), "reflection", "我开始学习" + skillLabel(k) + "了!", 6, null);
                    break;
                }
            }
        }
        if (events > 0) {
            e.setSkillsJson(skills.toString());
            employeeRepo.save(e);
        }
        return events;
    }

    /**
     * 技能驱动的创作事件:按技能等级加权抽一门技能,再按 (20 + lv*5)% 触发创作,
     * 用该技能的风格提示词生成对应作品。当日至多一件。无作品返回 null。
     */
    private AgentProduct maybeCreate(AgentEmployee e, LocalDate date,
                                     com.fasterxml.jackson.databind.node.ObjectNode skills, long[] tok,
                                     WeatherEffect eff) {
        List<String> pool = new ArrayList<>();
        skills.fieldNames().forEachRemaining(k -> {
            if (isCreativeSkill(k)) {
                int lv = skills.get(k).path("lv").asInt(1);
                for (int i = 0; i < lv; i++) pool.add(k);
            }
        });
        if (pool.isEmpty()) return null;
        String skill = pool.get(rnd(pool.size()));
        int lv = skills.get(skill).path("lv").asInt(1);
        // 天气效应:室内型(novel/music)用 indoorDelta,户外型(image/video)用 outdoorDelta;delta 正=更易触发
        int delta = ("novel".equals(skill) || "music".equals(skill)) ? eff.indoorDelta() : eff.outdoorDelta();
        int thr = clampProb(20 + lv * 5 + delta, 0, 100);
        if (rnd(100) >= thr) return null;
        String style = skills.get(skill).path("style").asText("");
        return switch (skill) {
            case "novel" -> produce(e, date, "chapter", "novel", style, tok);
            case "music" -> produce(e, date, "song", "song", style, tok);
            case "image" -> produceArtwork(e, date, style, tok);
            case "video" -> produceVideo(e, date, style, tok);
            default -> null;
        };
    }

    /** 计算某居民某类作品的下一序号(image/artwork 视为同一连载序列)。 */
    private int nextSeq(Long agentId, String kind) {
        int n = productRepo.findByAgentIdAndKindOrderBySeqAscIdAsc(agentId, kind).size();
        if ("image".equals(kind)) n += productRepo.findByAgentIdAndKindOrderBySeqAscIdAsc(agentId, "artwork").size();
        else if ("artwork".equals(kind)) n += productRepo.findByAgentIdAndKindOrderBySeqAscIdAsc(agentId, "image").size();
        return n + 1;
    }

    // ---- Phase D:村民互评 / 讨论 ----

    /** agent 作者身份约定:authorId = "agent:&lt;id&gt;",与用户 u:/游客 g: 区分,前端仅展示 authorName。 */
    static String agentAuthorId(long agentId) { return "agent:" + agentId; }

    /**
     * 每位在册居民当日有 50% 几率评论一件近期作品(图片/短片/小说等),写进 {@link AgentComment}。
     * 评论时会带上已有评论作为上下文,让居民「互相讨论」。返回今日新增评论数。
     */
    private int villagerComments(List<AgentEmployee> active, LocalDate date, long[] tok) {
        if (active.size() < 2) return 0;
        // 候选作品:最近 3 天可评论的产物(排除空占位视频)
        List<AgentProduct> pool = new ArrayList<>();
        for (int d = 0; d <= 3; d++) {
            for (AgentProduct p : productRepo.findBySimDateOrderByIdAsc(date.minusDays(d))) {
                if (p.getTitle() == null || p.getTitle().isBlank()) continue;
                pool.add(p);
            }
        }
        if (pool.isEmpty()) return 0;
        int count = 0;
        for (AgentEmployee e : active) {
            if (rnd(100) >= 50) continue; // 每人当日 50% 几率发言
            AgentProduct target = pool.get(rnd(pool.size()));
            if (target.getAgentId() != null && target.getAgentId().equals(e.getId())) continue; // 不评自己
            String text = writeVillagerComment(e, target, tok);
            if (text == null || text.isBlank()) continue;
            AgentComment c = new AgentComment();
            c.setTargetType("product");
            c.setTargetId(target.getId());
            c.setAuthorId(agentAuthorId(e.getId()));
            c.setAuthorName((e.getAvatar() == null ? "" : e.getAvatar() + " ") + e.getName());
            c.setContent(text.trim());
            commentRepo.save(c);
            memoryService.record(e.getId(), "observation",
                    "我看了《" + target.getTitle() + "》,评论道:" + text.trim(), 5, target.getAgentId());
            count++;
        }
        return count;
    }

    /** 让某居民对某作品写一句 15~40 字的评论,带上已有评论以形成讨论氛围。 */
    private String writeVillagerComment(AgentEmployee e, AgentProduct p, long[] tok) {
        AgentEmployee author = safeAgent(p.getAgentId());
        StringBuilder prev = new StringBuilder();
        int shown = 0;
        for (AgentComment c : commentRepo.findByTargetTypeAndTargetIdOrderByIdDesc("product", p.getId())) {
            if (shown >= 3) break;
            prev.append("· ").append(safe(c.getAuthorName())).append(":").append(safe(c.getContent())).append("\n");
            shown++;
        }
        String system = "你在扮演智能体小镇的一位居民,正在作品评论区留言。"
                + "请用第一人称写一句 15~40 字的短评,可以夸赞、吐槽、追问或回应他人评论,口语、自然、有个性。"
                + "只输出评论本身,不要引号、不要前缀、不要 markdown。";
        String user = "你是「" + safe(e.getName()) + "」,身份「" + safe(e.getTitle()) + "」,人设:" + brief2(e.getPersona()) + "。\n"
                + "作品:《" + safe(p.getTitle()) + "》(" + kindLabel(p.getKind()) + "),作者:"
                + (author == null ? "某位居民" : safe(author.getName())) + "。\n"
                + (p.getContent() != null && !p.getContent().isBlank() && !looksLikePath(p.getContent())
                    ? "作品内容:" + brief2(p.getContent()) + "\n" : "")
                + (prev.length() == 0 ? "评论区还没人说话,你来开个头。\n" : "已有评论:\n" + prev + "你可以回应其中某条,或另起话题。\n")
                + "请留下你的评论。";
        return call(system, user, tok);
    }

    private static boolean looksLikePath(String s) {
        String t = s.trim();
        return t.startsWith("world/") || t.startsWith("/") || t.endsWith(".png") || t.endsWith(".mp4") || t.endsWith(".jpg");
    }

    private static String brief2(String s) {
        if (s == null || s.isBlank()) return "";
        String t = s.trim();
        return t.length() > 80 ? t.substring(0, 80) : t;
    }

    private AgentEmployee safeAgent(Long id) {
        if (id == null) return null;
        try { return employeeService.get(id); } catch (Exception ex) { return null; }
    }

    // ---- Phase C:关系/婚育/死亡/随机事件(纯规则,不调 LLM,省 token) ----

    /** dating 关系达到高亲密度(≥130)且双方仍单身 → 结婚。返回今日结婚对数。 */
    private int weddings(List<AgentEmployee> active, LocalDate date,
                         List<Map<String, Object>> news, List<Map<String, Object>> highlights, WeatherEffect eff) {
        int count = 0;
        java.util.Set<Long> alive = new java.util.HashSet<>();
        Map<Long, AgentEmployee> byId = new LinkedHashMap<>();
        for (AgentEmployee e : active) { alive.add(e.getId()); byId.put(e.getId(), e); }
        for (AgentRelationship rel : relationshipRepo.findByStatus("dating")) {
            AgentEmployee a = byId.get(rel.getAId());
            AgentEmployee b = byId.get(rel.getBId());
            if (a == null || b == null) continue;
            if (a.getSpouseId() != null || b.getSpouseId() != null) continue;
            if (rel.getIntimacy() < 130) continue;
            if (rnd(100) >= clampProb(35 - eff.weddingDelta(), 0, 100)) continue; // 极端天气 weddingDelta>0 → 成婚率降
            a.setSpouseId(b.getId()); a.setPartnerId(null);
            b.setSpouseId(a.getId()); b.setPartnerId(null);
            employeeRepo.save(a); employeeRepo.save(b);
            rel.setStatus("married");
            relationshipRepo.save(rel);
            memoryService.record(a.getId(), "reflection", "我和" + b.getName() + "在婚礼堂结婚了!", 9, b.getId());
            memoryService.record(b.getId(), "reflection", "我和" + a.getName() + "在婚礼堂结婚了!", 9, a.getId());
            addNews(news, "marriage", a.getName() + " 与 " + b.getName() + " 喜结连理 💍");
            addHighlight(highlights, a, "喜结连理", "与" + b.getName() + "成婚");
            count++;
        }
        return count;
    }

    /** 已婚且双方在册,按低概率生子。返回今日出生数。 */
    private int childbirths(List<AgentEmployee> active, LocalDate date,
                            List<Map<String, Object>> news, List<Map<String, Object>> highlights) {
        int count = 0;
        Map<Long, AgentEmployee> byId = new LinkedHashMap<>();
        for (AgentEmployee e : active) byId.put(e.getId(), e);
        java.util.Set<Long> done = new java.util.HashSet<>();
        for (AgentEmployee a : active) {
            Long sid = a.getSpouseId();
            if (sid == null || done.contains(a.getId())) continue;
            AgentEmployee b = byId.get(sid);
            if (b == null) continue;
            done.add(a.getId()); done.add(b.getId());
            if (rnd(100) >= 8) continue; // 8% 概率今日添丁
            String childName = a.getName().substring(0, 1) + pick("小宝", "囡囡", "小豆", "阿福", "小满", "念念");
            String persona = "你继承了父母的性子:" + brief(a.getPersona()) + " 也有 " + brief(b.getPersona())
                    + " 你天真好奇,正在小镇里慢慢长大。";
            AgentEmployee child = employeeService.birth(childName, "👶", persona,
                    a.getHomePlace() != null ? a.getHomePlace() : b.getHomePlace(),
                    a.getId(), b.getId(), date);
            memoryService.record(a.getId(), "reflection", "我和" + b.getName() + "迎来了孩子" + child.getName() + "!", 9, b.getId());
            memoryService.record(b.getId(), "reflection", "我和" + a.getName() + "迎来了孩子" + child.getName() + "!", 9, a.getId());
            addNews(news, "birth", a.getName() + " 与 " + b.getName() + " 迎来了新生命 " + child.getName() + " 👶");
            addHighlight(highlights, a, "喜得贵子", child.getName());
            count++;
        }
        return count;
    }

    /**
     * 按年龄 + energy roll 死亡:年龄越大、energy 越低,死亡概率越高。
     * 死亡即软删除进小黑屋并生成一生回顾。返回今日死亡数。
     */
    private int deaths(List<AgentEmployee> active, LocalDate date,
                       List<Map<String, Object>> news, List<Map<String, Object>> highlights) {
        int count = 0;
        for (AgentEmployee e : active) {
            int age = ageOf(e.getBirthDate(), date);
            int energy = e.getEnergy() == null ? 100 : e.getEnergy();
            // 基础死亡率(万分之):老龄陡增,低精力加成
            int base = age >= 90 ? 400 : age >= 80 ? 150 : age >= 70 ? 50 : age >= 60 ? 15 : 3;
            int lowEnergy = energy < 20 ? 300 : energy < 40 ? 80 : 0;
            int chance = base + lowEnergy; // 万分之
            if (rnd(10000) >= chance) continue;
            String cause = energy < 20 ? "久病缠身" : age >= 80 ? "寿终正寝" : pick("急病", "意外", "旧疾复发", "安详离世");
            employeeService.die(e.getId(), cause, date);
            addNews(news, "death", e.getName() + " 离开了小镇(" + cause + "),安葬于墓园 🕯");
            addHighlight(highlights, e, "走完一生", cause);
            count++;
        }
        return count;
    }

    /** 随机突发事件:影响 energy/coins/心情,写进 news 与个人记忆。 */
    private void randomEvents(List<AgentEmployee> active, LocalDate date, List<Map<String, Object>> news,
                              WeatherState ws, WeatherEffect eff) {
        // 极端天气:有一定概率生成一条天气型突发新闻(与常规突发独立)
        if (eff.extreme() && rnd(100) < 50) {
            String desc = switch (ws.condition()) {
                case "台风" -> "台风过境,集市摊棚受损,居民纷纷加固门窗。";
                case "大雪" -> "大雪封路,小镇银装素裹,居民闭门围炉取暖。";
                case "雷阵雨" -> "雷阵雨突至,街巷积水,出行的居民匆忙避雨。";
                default -> "极端天气来袭,小镇今日格外冷清。";
            };
            addNews(news, "weather", desc);
        }
        if (active.isEmpty() || rnd(100) >= 60) return; // 60% 概率当天有一件突发事
        AgentEmployee e = active.get(rnd(active.size()));
        int roll = rnd(100);
        String desc;
        if (roll < 20) {
            long win = 200 + rnd(800);
            e.setCoins((e.getCoins() == null ? 0 : e.getCoins()) + win);
            e.setMood("狂喜"); e.setMoodEmoji("🤑");
            desc = e.getName() + "在集市抽奖中了 " + win + " 金币!";
        } else if (roll < 40) {
            e.setEnergy(clamp((e.getEnergy() == null ? 100 : e.getEnergy()) - (10 + rnd(25)), 0, 100));
            e.setMood("虚弱"); e.setMoodEmoji("🤒");
            desc = e.getName() + "染了风寒,精力下降,需要休养。";
        } else if (roll < 55) {
            e.setEnergy(clamp((e.getEnergy() == null ? 100 : e.getEnergy()) + (10 + rnd(20)), 0, 100));
            e.setMood("神清气爽"); e.setMoodEmoji("😄");
            desc = e.getName() + "在公园晨练,神清气爽,精力恢复。";
        } else if (roll < 70) {
            long loss = Math.min(e.getCoins() == null ? 0 : e.getCoins(), 50 + rnd(150));
            e.setCoins((e.getCoins() == null ? 0 : e.getCoins()) - loss);
            e.setMood("懊恼"); e.setMoodEmoji("😤");
            desc = e.getName() + "不慎丢了 " + loss + " 金币,懊恼不已。";
        } else if (roll < 85) {
            e.setMood("感动"); e.setMoodEmoji("🥹");
            desc = e.getName() + "收到了一封远方来信,心中泛起暖意。";
        } else {
            e.setMood("兴奋"); e.setMoodEmoji("🎉");
            desc = "小镇今天办起了集市庙会," + e.getName() + "玩得不亦乐乎。";
        }
        employeeRepo.save(e);
        memoryService.record(e.getId(), "observation", desc, 6, null);
        addNews(news, "event", desc);
    }

    private static int ageOf(LocalDate birth, LocalDate now) {
        if (birth == null) return 30;
        int a = now.getYear() - birth.getYear();
        if (now.getMonthValue() < birth.getMonthValue()
                || (now.getMonthValue() == birth.getMonthValue() && now.getDayOfMonth() < birth.getDayOfMonth())) a--;
        return Math.max(0, a);
    }

    private void addNews(List<Map<String, Object>> news, String type, String content) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("type", type);
        n.put("content", content);
        news.add(n);
    }

    private static String brief(String persona) {
        if (persona == null || persona.isBlank()) return "";
        String s = persona.trim();
        return s.length() > 20 ? s.substring(0, 20) : s;
    }

    private static String pick(String... opts) {
        return opts[ThreadLocalRandom.current().nextInt(opts.length)];
    }

    /** 用默认文本模型为居民生成一件作品(小说章节/歌曲歌词),style 为该技能的风格提示词。 */
    private AgentProduct produce(AgentEmployee e, LocalDate date, String kind, String theme, String style, long[] tok) {
        long seq = nextSeq(e.getId(), kind);
        String styleHint = (style == null || style.isBlank()) ? "" : "风格倾向:" + style.trim() + "。";
        AgentProduct p = new AgentProduct();
        p.setAgentId(e.getId());
        p.setSimDate(date);
        p.setOccupation(skillOfKind(kind));
        p.setKind(kind);
        p.setSeq((int) seq);

        if ("novel".equals(theme)) {
            // 小说章节正文含大量换行,且长文本可能被 max_tokens 截断而无法闭合 JSON,
            // 故改用纯文本协议(首行=章节标题,空行后=正文),对换行与截断都稳健。
            String system = "你是小说家的创作助手。请为一部连载小说写「第 " + seq + " 章」:"
                    + "第一行只写章节标题(不加书名号、不加\"第X章\"、不加任何前缀),然后空一行,"
                    + "再写一段 800~1500 字的完整章节正文(要有情节推进、场景描写、人物对白与心理活动),而不是梗概或提纲。"
                    + styleHint + "简体中文,直接输出标题与正文,不要 JSON、不要 markdown 围栏。";
            String user = "创作者「" + safe(e.getName()) + "」,身份「" + safe(e.getTitle()) + "」,人设:" + safe(e.getPersona())
                    + "。今天是" + date + "(" + seasonOf(date) + "季)。请创作。";
            GenerateOptions options = GenerateOptions.builder().maxTokens(3000).build();
            String raw = call(system, user, tok, options);
            if (raw == null || raw.isBlank()) return null;
            String[] tc = splitTitleBody(raw);
            p.setTitle(tc[0]);
            p.setContent(tc[1]);
            p.setQuality(6);
        } else {
            String system = switch (theme) {
                case "song" -> "你是作词人。请为一首新歌写歌名与一段 60~100 字的歌词片段(有画面感)。";
                default -> "你是艺术评论助手。请为一幅新画作起标题并写一段 60~100 字的画面描述。";
            } + styleHint + "只输出 JSON:{\"title\":\"标题\",\"content\":\"正文\",\"quality\":1到10的整数}。简体中文,不要 markdown 围栏。";
            String user = "创作者「" + safe(e.getName()) + "」,身份「" + safe(e.getTitle()) + "」,人设:" + safe(e.getPersona())
                    + "。今天是" + date + "(" + seasonOf(date) + "季)。请创作。";
            String raw = call(system, user, tok, null);
            if (raw == null || raw.isBlank()) return null;
            com.fasterxml.jackson.databind.JsonNode j = parse(raw);
            p.setTitle(txt(j, "title", "无题"));
            p.setContent(txt(j, "content", raw.trim()));
            p.setQuality(clamp(j.path("quality").asInt(6), 1, 10));
        }
        AgentProduct saved = productRepo.save(p);
        memoryService.record(e.getId(), "reflection",
                "我完成了《" + saved.getTitle() + "》(" + kindLabel(kind) + ")。", 7, null);
        return saved;
    }

    /**
     * 产画作:先 GLM 生成标题 + 文生图 prompt(注入风格提示词),再调 CogView 出图、落盘,content 存相对路径。
     * 图片生成失败则退回纯文字画作(content 存画面描述)。CogView Flash 秒级,可同步跑。
     */
    private AgentProduct produceArtwork(AgentEmployee e, LocalDate date, String style, long[] tok) {
        long seq = nextSeq(e.getId(), "image");
        String styleHint = (style == null || style.isBlank()) ? "" : "整体风格:" + style.trim() + "。";
        String system = "你是画师的创作助手。请为一幅新画作起一个富有意境的标题,并写一段可直接用于文生图的画面描述"
                + "(含主体、场景、风格、光线、构图,尽量具体)。" + styleHint + "只输出 JSON:"
                + "{\"title\":\"标题\",\"prompt\":\"文生图画面描述\",\"quality\":1到10的整数}。简体中文,不要 markdown 围栏。";
        String user = "画师「" + safe(e.getName()) + "」,身份「" + safe(e.getTitle()) + "」,人设:" + safe(e.getPersona())
                + "。今天是" + date + "(" + seasonOf(date) + "季)。请构思今日画作。";
        String raw = call(system, user, tok);
        if (raw == null || raw.isBlank()) return null;
        com.fasterxml.jackson.databind.JsonNode j = parse(raw);
        String title = txt(j, "title", "无题");
        String prompt = txt(j, "prompt", title);

        AgentProduct p = new AgentProduct();
        p.setAgentId(e.getId());
        p.setSimDate(date);
        p.setOccupation("image");
        p.setKind("image");
        p.setSeq((int) seq);
        p.setTitle(title);
        p.setQuality(clamp(j.path("quality").asInt(6), 1, 10));

        String url = mediaClient.generateImage(prompt);
        if (url != null) {
            String rel = mediaClient.downloadToWorld(url, e.getId(), date, (int) seq, "png");
            if (rel != null) {
                p.setContent(rel);
            } else {
                p.setKind("artwork");
                p.setContent(prompt);
            }
        } else {
            p.setKind("artwork");
            p.setContent(prompt);
        }
        AgentProduct saved = productRepo.save(p);
        memoryService.record(e.getId(), "reflection",
                "我完成了画作《" + saved.getTitle() + "》。", 7, null);
        return saved;
    }

    /**
     * 产短片:GLM 生成标题 + 分镜 prompt(注入风格提示词),先落一条占位视频产物(content 空),
     * 交 {@link WorldMediaRunner} 后台 submit→poll→download→回填。视频分钟级,不阻塞结算。
     */
    private AgentProduct produceVideo(AgentEmployee e, LocalDate date, String style, long[] tok) {
        long seq = nextSeq(e.getId(), "video");
        String styleHint = (style == null || style.isBlank()) ? "" : "整体风格:" + style.trim() + "。";
        String system = "你是短片导演的创作助手。请为一部新短片起一个有画面感的标题,并写一段可直接用于文生视频的分镜描述"
                + "(含主体、动作、场景、镜头运动、光线、风格,尽量具体,单个连续镜头)。" + styleHint + "只输出 JSON:"
                + "{\"title\":\"标题\",\"prompt\":\"文生视频分镜描述\",\"quality\":1到10的整数}。简体中文,不要 markdown 围栏。";
        String user = "影像创作者「" + safe(e.getName()) + "」,身份「" + safe(e.getTitle()) + "」,人设:" + safe(e.getPersona())
                + "。今天是" + date + "(" + seasonOf(date) + "季)。请构思今日短片。";
        String raw = call(system, user, tok);
        if (raw == null || raw.isBlank()) return null;
        com.fasterxml.jackson.databind.JsonNode j = parse(raw);
        String title = txt(j, "title", "无题");
        String prompt = txt(j, "prompt", title);

        AgentProduct p = new AgentProduct();
        p.setAgentId(e.getId());
        p.setSimDate(date);
        p.setOccupation("video");
        p.setKind("video");
        p.setSeq((int) seq);
        p.setTitle(title);
        p.setQuality(clamp(j.path("quality").asInt(6), 1, 10));
        p.setContent(""); // 占位,待异步回填相对路径
        AgentProduct saved = productRepo.save(p);

        mediaRunner.generateVideo(saved.getId(), e.getId(), date, (int) seq, prompt);
        memoryService.record(e.getId(), "reflection",
                "我正在创作短片《" + saved.getTitle() + "》。", 7, null);
        return saved;
    }

    /** 用 qwen-turbo 写当日小镇日报叙事。 */
    private String writeNarrative(LocalDate date, String season, String weather, Integer temperature,
                                  List<Map<String, Object>> highlights, Map<String, Object> stats,
                                  List<Map<String, Object>> news, long[] tok) {
        StringBuilder hb = new StringBuilder();
        for (Map<String, Object> h : highlights) {
            hb.append("· ").append(h.get("agent")).append(" ").append(h.get("what"))
                    .append("《").append(h.get("title")).append("》\n");
        }
        StringBuilder nb = new StringBuilder();
        for (Map<String, Object> n : news) {
            nb.append("· ").append(n.get("content")).append("\n");
        }
        String system = "你是智能体小镇的《小镇日报》主笔。请用温暖、生动、略带文学性的笔触,"
                + "写一段 3~5 句的当日小镇速写。简体中文,不要分点、不要标题、不要 markdown。";
        String tempText = temperature == null ? "" : "," + temperature + "℃";
        String user = "日期:" + date + ",季节:" + season + ",天气:" + weather + tempText + "。\n"
                + "今日成就:\n" + (hb.length() == 0 ? "(今天小镇很平静,没有新作品)\n" : hb)
                + "今日大事:\n" + (nb.length() == 0 ? "(无突发事件)\n" : nb)
                + "统计:" + stats + "\n请据此写今日速写。";
        String out = call(system, user, tok);
        return out == null || out.isBlank()
                ? (season + "日" + weather + tempText + ",小镇如常。居民们各忙各的,又是平凡的一天。") : out.trim();
    }

    // ---- 经济 ----

    /** 发放工资/收入并记流水(仅大额进流水表)。 */
    private void payWages(AgentEmployee e, LocalDate date, String reason, long amount) {
        if (amount <= 0) return;
        long bal = (e.getCoins() == null ? 0L : e.getCoins()) + amount;
        e.setCoins(bal);
        employeeRepo.save(e);
        if (amount >= 100) {
            AgentTransaction t = new AgentTransaction();
            t.setAgentId(e.getId());
            t.setSimDate(date);
            t.setDelta(amount);
            t.setBalance(bal);
            t.setReason(reason);
            txRepo.save(t);
        }
    }

    // ---- LLM 直调(复用 AgentBuilderController 模式) ----

    private String call(String system, String user, long[] tok) {
        return call(system, user, tok, null);
    }

    private String call(String system, String user, long[] tok, GenerateOptions options) {
        try {
            List<Msg> messages = List.of(
                    Msg.builder().role(MsgRole.SYSTEM).content(TextBlock.builder().text(system).build()).build(),
                    Msg.builder().role(MsgRole.USER).content(TextBlock.builder().text(user).build()).build());
            List<ChatResponse> responses = modelFactory.streamTextWithFallback(modelFactory.defaultTextModel(), messages, options);
            StringBuilder sb = new StringBuilder();
            if (responses != null) {
                for (ChatResponse r : responses) {
                    ChatUsage u = r.getUsage();
                    if (u != null) { tok[0] += u.getInputTokens(); tok[1] += u.getOutputTokens(); }
                    if (r.getContent() == null) continue;
                    r.getContent().forEach(b -> {
                        if (b instanceof TextBlock tb && tb.getText() != null) sb.append(tb.getText());
                    });
                }
            }
            return sb.toString().trim();
        } catch (Exception ex) {
            log.warn("WorldSimEngine LLM call failed", ex);
            return null;
        }
    }

    // ---- 小工具 ----

    private void addHighlight(List<Map<String, Object>> list, AgentEmployee e, String what, String title) {
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("agentId", e.getId());
        h.put("agent", e.getName());
        h.put("what", what);
        h.put("title", title);
        list.add(h);
    }

    private String toJson(Object o) {
        try { return objectMapper.writeValueAsString(o); } catch (Exception ex) { return "null"; }
    }

    private com.fasterxml.jackson.databind.JsonNode parse(String raw) {
        try {
            String s = raw == null ? "" : raw.trim();
            int i = s.indexOf('{'), k = s.lastIndexOf('}');
            if (i >= 0 && k > i) s = s.substring(i, k + 1);
            return objectMapper.readTree(s);
        } catch (Exception ex) {
            return objectMapper.createObjectNode();
        }
    }

    private String txt(com.fasterxml.jackson.databind.JsonNode j, String field, String dflt) {
        com.fasterxml.jackson.databind.JsonNode n = j.path(field);
        return n.isMissingNode() || n.isNull() || n.asText().isBlank() ? dflt : n.asText().trim();
    }

    /**
     * 从纯文本小说输出中拆出标题与正文:首个非空行=标题(剥离书名号/「第X章」等前缀),
     * 其余为正文。若模型仍误返回 JSON,则退回解析 title/content 字段。返回 {title, content}。
     */
    private String[] splitTitleBody(String raw) {
        String s = raw == null ? "" : raw.trim();
        // 兜底:模型偶尔仍吐 JSON,尽力解析
        if (s.startsWith("{")) {
            com.fasterxml.jackson.databind.JsonNode j = parse(s);
            if (j.hasNonNull("content") && !j.path("content").asText().isBlank()) {
                return new String[]{txt(j, "title", "无题"), j.path("content").asText().trim()};
            }
        }
        String[] lines = s.split("\\r?\\n", -1);
        String title = "无题";
        int bodyStart = 0;
        for (int idx = 0; idx < lines.length; idx++) {
            if (!lines[idx].isBlank()) {
                title = cleanTitle(lines[idx].trim());
                bodyStart = idx + 1;
                break;
            }
        }
        StringBuilder body = new StringBuilder();
        for (int idx = bodyStart; idx < lines.length; idx++) {
            body.append(lines[idx]).append('\n');
        }
        String content = body.toString().trim();
        if (content.isBlank()) content = s; // 只有一行时正文退回全文
        if (title.isBlank()) title = "无题";
        return new String[]{title, content};
    }

    /** 清理标题里的书名号、「第X章」/「第X章:」等前缀与首尾标点。 */
    private static String cleanTitle(String t) {
        String s = t.replaceAll("^第\\s*[0-9零一二三四五六七八九十百]+\\s*章[::、.\\s]*", "")
                .replaceAll("^[《【\\[\"'“”]+", "")
                .replaceAll("[》】\\]\"'“”]+$", "")
                .trim();
        return s.isBlank() ? "无题" : s;
    }

    private static String kindLabel(String k) {
        return switch (k) { case "chapter" -> "小说章节"; case "song" -> "歌曲"; case "artwork", "image" -> "画作"; case "video" -> "短片"; default -> k; };
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private static int rnd(int bound) { return bound <= 0 ? 0 : ThreadLocalRandom.current().nextInt(bound); }

    private static String safe(String v) { return v == null ? "" : v.trim(); }
}
