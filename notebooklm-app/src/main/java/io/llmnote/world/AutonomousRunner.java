package io.llmnote.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.harness.agent.HarnessAgent;
import io.llmnote.llm.ChatModelFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 智能体小镇式的<b>自主行动引擎</b>。每 30s 醒来一次(独立 bean,规避 @Scheduled 自调用坑),
 * 读全局 {@link WorldSettings}:总开关关就直接睡;开则按 {@code intervalSeconds} 节流,
 * 每个节拍<b>只挑一名</b> active 居民(轮转,省 token)让其基于长期记忆自主行动一次:
 * <b>去某个具名地点</b>(见 {@link TownMap})/ 思考 / 反思 / <b>主动去找另一位居民说话</b>。
 *
 * <p>行动围绕地点展开:goto 走向地点、talk 走到对方所在地点,坐标落到 {@link TownMap#jitter};
 * 结果写入 {@link AgentAction}(带 place/scene,供地图浮动场景卡)与 {@link AgentMemory}(长期记忆);
 * 若是「找人说话」,给对方也写一条记忆,让互动在双方记忆里留痕。默认用最便宜模型(qwen-turbo)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AutonomousRunner {

    private final ObjectMapper objectMapper;
    private final WorldSettingsService settingsService;
    private final AgentEmployeeRepository employeeRepo;
    private final AgentActionRepository actionRepo;
    private final AgentMemoryService memoryService;
    private final EmployeeAgentFactory agentFactory;
    private final ChatModelFactory modelFactory;
    private final WorldSimEngine simEngine;
    private final RelationshipService relationshipService;

    /** 轮转指针:每个节拍推进一名居民。 */
    private final AtomicInteger cursor = new AtomicInteger(0);
    private volatile long lastTickAt = 0L;

    @Scheduled(fixedDelay = 30_000L)
    public void tick() {
        try {
            WorldSettings s = settingsService.get();
            if (s.getAutonomousEnabled() == null || !s.getAutonomousEnabled()) return;

            long now = System.currentTimeMillis();
            long gapMs = Math.max(30, s.getIntervalSeconds()) * 1000L;
            if (now - lastTickAt < gapMs) return;
            lastTickAt = now;

            advanceClock(s); // 推进小镇内在时钟,跨午夜触发当日结算

            List<AgentEmployee> active = new ArrayList<>();
            for (AgentEmployee e : employeeRepo.findByStatusOrderByIdAsc("active")) {
                if (e.getAutonomousActive() == null || e.getAutonomousActive()) active.add(e);
            }
            if (active.isEmpty()) return;

            AgentEmployee actor = active.get(Math.floorMod(cursor.getAndIncrement(), active.size()));
            act(actor, active, s.getModel(), s);
        } catch (Exception ex) {
            log.warn("autonomous tick failed", ex);
        }
    }

    /**
     * 推进小镇内在时钟:sim_minute += minutes_per_tick;跨过 1440 → sim_date+1 并触发
     * 前一天的 {@link WorldSimEngine#dailySettlement}。首次运行(sim_date 为空)以今天为起点。
     */
    private void advanceClock(WorldSettings s) {
        java.time.LocalDate date = s.getSimDate();
        if (date == null) {
            s.setSimDate(java.time.LocalDate.now());
            if (s.getSimMinute() == null) s.setSimMinute(480);
            if (s.getMinutesPerTick() == null) s.setMinutesPerTick(120);
            s.setSeason(WorldSimEngine.seasonOf(s.getSimDate()));
            settingsService.save(s);
            return;
        }
        int step = s.getMinutesPerTick() == null ? 120 : Math.max(1, s.getMinutesPerTick());
        int min = (s.getSimMinute() == null ? 480 : s.getSimMinute()) + step;
        while (min >= 1440) {
            min -= 1440;
            java.time.LocalDate prev = date;
            date = date.plusDays(1);
            try {
                WorldDailyReport r = simEngine.dailySettlement(prev);
                if (r != null) { s.setSeason(r.getSeason()); s.setWeather(r.getWeather()); s.setTemperature(r.getTemperature()); }
            } catch (Exception ex) {
                log.warn("daily settlement failed for {}", prev, ex);
            }
        }
        s.setSimDate(date);
        s.setSimMinute(min);
        settingsService.save(s);
    }

    /** 让 actor 自主行动一次并落库。 */
    private void act(AgentEmployee actor, List<AgentEmployee> peers, String modelName, WorldSettings s) {
        HarnessAgent agent = agentFactory.forEmployee(actor, modelName);
        String prompt = buildPrompt(actor, peers, s);

        long[] tok = {0L, 0L};
        String raw = ask(agent, prompt, tok);
        JsonNode j = parse(raw);

        String type = text(j, "action", "think");   // goto | think | talk | reflect
        String content = text(j, "content", raw.isBlank() ? "(发呆)" : raw.trim());
        String scene = text(j, "scene", null);
        String mood = text(j, "mood", null);
        String moodEmoji = text(j, "moodEmoji", null);
        Long targetId = matchTarget(j.path("target").asText(null), actor, peers);

        // 决定本次行动发生地点 + 更新 actor 坐标/所在地
        String place = settleLocation(actor, type, j.path("place").asText(null), targetId, peers);

        if (mood != null && !mood.isBlank()) actor.setMood(mood.trim());
        if (moodEmoji != null && !moodEmoji.isBlank()) actor.setMoodEmoji(moodEmoji.trim());
        employeeRepo.save(actor);

        BigDecimal cost = BigDecimal.valueOf(modelFactory.costRmb(modelName, tok[0], tok[1]));
        AgentAction a = new AgentAction();
        a.setAgentId(actor.getId());
        a.setType(type);
        a.setContent(content);
        a.setTargetAgentId(targetId);
        a.setPlace(place);
        a.setScene(scene);
        a.setInputTokens(tok[0]);
        a.setOutputTokens(tok[1]);
        a.setCostRmb(cost);
        actionRepo.save(a);

        // 写自己的长期记忆
        memoryService.record(actor.getId(), "talk".equals(type) ? "dialogue" : "action",
                content, "reflect".equals(type) ? 6 : 4, targetId);

        // 找人说话 → 给对方也留一条记忆,让互动双向留痕;并增进两人亲密度
        if ("talk".equals(type) && targetId != null) {
            memoryService.record(targetId, "dialogue",
                    actor.getName() + "来找我说:" + content, 4, actor.getId());
            relationshipService.bump(actor.getId(), targetId, 3 + (int) (Math.random() * 3));
        }
        log.info("autonomous act agent={} type={} place={} target={} tokens={}/{}",
                actor.getName(), type, place, targetId, tok[0], tok[1]);
    }

    /**
     * 结算本次行动的地点并更新 actor 坐标/所在地,返回行动发生地点 key:
     * <ul>
     *   <li>goto:走向 place 指定地点(模糊匹配)。</li>
     *   <li>talk:走到 target 当前所在地点。</li>
     *   <li>think/reflect:留在原地(actor 当前 location,缺省 plaza)。</li>
     * </ul>
     */
    private String settleLocation(AgentEmployee actor, String type, String placeText,
                                  Long targetId, List<AgentEmployee> peers) {
        TownMap.Place dest;
        if ("talk".equals(type) && targetId != null) {
            String targetLoc = null;
            for (AgentEmployee p : peers) {
                if (p.getId().equals(targetId)) { targetLoc = p.getLocation(); break; }
            }
            dest = TownMap.byKey(targetLoc);
        } else if ("goto".equals(type)) {
            dest = TownMap.match(placeText);
        } else {
            dest = TownMap.byKey(actor.getLocation()); // 原地
        }
        double[] pos = TownMap.jitter(dest);
        actor.setLocation(dest.key());
        actor.setPosX(pos[0]);
        actor.setPosY(pos[1]);
        return dest.key();
    }

    private String buildPrompt(AgentEmployee actor, List<AgentEmployee> peers, WorldSettings s) {
        StringBuilder places = new StringBuilder();
        for (TownMap.Place p : TownMap.all()) {
            places.append(p.emoji()).append(p.name()).append("(").append(p.key()).append(") ");
        }
        String hereName = TownMap.byKey(actor.getLocation()).name();

        StringBuilder others = new StringBuilder();
        for (AgentEmployee p : peers) {
            if (p.getId().equals(actor.getId())) continue;
            others.append("「").append(p.getName()).append("」(").append(p.getTitle())
                    .append(",现在在").append(TownMap.byKey(p.getLocation()).name()).append(") ");
        }

        // 内在时间语境:现在几点、季节、天气、你的作息与职业
        int min = s.getSimMinute() == null ? 480 : s.getSimMinute();
        String clock = String.format("%02d:%02d", min / 60, min % 60);
        String phase = phaseOf(min, actor.getScheduleJson());
        String season = s.getSeason() == null ? "" : s.getSeason() + "季";
        String weather = s.getWeather() == null ? "" : s.getWeather();
        if (s.getTemperature() != null) weather = weather + " " + s.getTemperature() + "℃";
        String occ = actor.getOccupation();
        String occLine = occ == null || occ.isBlank() ? ""
                : "你的职业是「" + occLabel(occ) + "」,工作时段可以专注本职创作。\n";

        return "现在是小镇时间 " + clock + "(" + phase + ")," + season + weather + "。你此刻在「" + hereName + "」。\n"
                + occLine
                + "镇上的地点有:" + places + "\n"
                + "镇上还有这些居民,你可以去他们所在的地方找他们聊天:"
                + (others.length() == 0 ? "(暂无)" : others) + "\n"
                + "请结合此刻的时间、你的作息与心情决定做一件合理的事(深夜就休息、白天再活动)。"
                + "只输出一个 JSON 对象,不要多余文字,格式:\n"
                + "{\"action\":\"goto|think|talk|reflect\","
                + "\"place\":\"若 action=goto,填你要去的地点名(如 咖啡馆),否则填你当前所在地\","
                + "\"target\":\"若 action=talk,填你要找的居民名字,否则留空\","
                + "\"content\":\"一句话描述你做了什么/说了什么(第一人称,围绕地点或对象)\","
                + "\"scene\":\"≤8字场景短标题,如 在咖啡馆闲聊\","
                + "\"mood\":\"你此刻的心情(2-4字)\",\"moodEmoji\":\"一个 emoji\"}";
    }

    /** 由当前分钟 + 作息模板判断时段语境(缺省模板)。 */
    private String phaseOf(int min, String scheduleJson) {
        int wake = 7, sleep = 23;
        int workStart = 9, workEnd = 18;
        try {
            if (scheduleJson != null && !scheduleJson.isBlank()) {
                JsonNode j = objectMapper.readTree(scheduleJson);
                if (j.has("wake")) wake = j.get("wake").asInt(wake);
                if (j.has("sleep")) sleep = j.get("sleep").asInt(sleep);
                JsonNode w = j.path("work");
                if (w.isArray() && w.size() >= 2) {
                    workStart = w.get(0).asInt(workStart);
                    workEnd = w.get(w.size() - 1).asInt(workEnd);
                }
            }
        } catch (Exception ignore) { /* 用缺省 */ }
        int h = min / 60;
        if (h < wake || h >= sleep) return "深夜休息时间";
        if (h >= workStart && h < workEnd) return "工作时间";
        return "休闲时间";
    }

    private String occLabel(String occ) {
        return switch (occ) {
            case "writer" -> "作家";
            case "singer" -> "歌手";
            case "painter" -> "画师";
            default -> occ;
        };
    }

    /** 按名字模糊匹配一位目标居民(不含自己)。 */
    private Long matchTarget(String name, AgentEmployee self, List<AgentEmployee> peers) {
        if (name == null || name.isBlank()) return null;
        String n = name.trim();
        for (AgentEmployee p : peers) {
            if (p.getId().equals(self.getId())) continue;
            if (p.getName().equals(n) || n.contains(p.getName())) return p.getId();
        }
        return null;
    }

    private String ask(HarnessAgent agent, String prompt, long[] tok) {
        Msg reply = agent.call(prompt, RuntimeContext.empty()).block();
        if (reply == null) return "";
        ChatUsage u = reply.getChatUsage();
        if (u != null) { tok[0] += u.getInputTokens(); tok[1] += u.getOutputTokens(); }
        String text = reply.getTextContent();
        return text == null ? "" : text.trim();
    }

    /** 宽松解析 LLM 返回的 JSON:剥离 ```json 围栏、截取首个 {...}。 */
    private JsonNode parse(String raw) {
        try {
            String s = raw == null ? "" : raw.trim();
            int i = s.indexOf('{'), j = s.lastIndexOf('}');
            if (i >= 0 && j > i) s = s.substring(i, j + 1);
            return objectMapper.readTree(s);
        } catch (Exception ex) {
            return objectMapper.createObjectNode();
        }
    }

    private String text(JsonNode j, String field, String dflt) {
        JsonNode n = j.path(field);
        return n.isMissingNode() || n.isNull() || n.asText().isBlank() ? dflt : n.asText().trim();
    }
}
