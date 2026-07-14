package io.llmnote.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.llmnote.llm.ChatModelFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 沙盒快进的异步执行体(纯规则引擎)。独立于 {@link SandboxService} 是为了让 {@code @Async} 生效
 * —— Spring 的 @Async 走代理,同类自调用会退化成同步,阻塞「发起沙盒」的 HTTP 请求。
 *
 * <p>以选定居民的<b>内存快照</b>为起点,按年推进关系/婚育/死亡/经济/产物/突发事件,逐条落
 * {@link SandboxEvent}(供回放);仅在最终写一段世界报告时调用 qwen-turbo(省 token)。
 * 全程不触碰真实世界的任何表。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SandboxRunner {

    private final SandboxRunRepository runRepo;
    private final SandboxEventRepository eventRepo;
    private final ChatModelFactory modelFactory;
    private final ObjectMapper objectMapper;

    /** 每年产物数期望(粗略),用于统计与叙事。 */
    private static final Map<String, Integer> YEARLY_OUTPUT = Map.of(
            "writer", 200, "singer", 40, "painter", 80);

    /** 沙盒内一名居民的内存快照(不落库,不影响真实世界)。 */
    static final class SimAgent {
        long id;          // 真实居民 id;沙盒新生儿用负数 id
        String name;
        String occupation;
        int age;
        int energy;
        long coins;
        long spouseId;    // 0 = 单身
        boolean alive = true;
        int products;     // 累计产物数
        SimAgent(long id, String name, String occupation, int age, int energy, long coins) {
            this.id = id; this.name = name; this.occupation = occupation;
            this.age = age; this.energy = energy; this.coins = coins;
        }
    }

    /** 一次沙盒运行(异步,专属线程池,避免被其它世界任务饿死)。members 为选定居民快照种子。 */
    @Async("sandboxExecutor")
    public void run(Long runId, List<AgentEmployee> members, int years, LocalDate startDate) {
        SandboxRun run = runRepo.findById(runId).orElse(null);
        if (run == null) return;
        run.setStatus("RUNNING");
        runRepo.save(run);
        long[] tok = {0L, 0L};
        int[] seq = {0};
        try {
            List<SimAgent> pop = new ArrayList<>();
            for (AgentEmployee e : members) {
                pop.add(new SimAgent(e.getId(), e.getName(), e.getOccupation(),
                        ageOf(e.getBirthDate(), startDate),
                        e.getEnergy() == null ? 100 : e.getEnergy(),
                        e.getCoins() == null ? 0L : e.getCoins()));
            }
            long nextChildId = -1;
            int totalBirths = 0, totalDeaths = 0, totalMarriages = 0;
            long totalProducts = 0;

            for (int y = 1; y <= years; y++) {
                LocalDate yDate = startDate.plusYears(y);
                // 关系升温 + 结婚(随机在存活单身者间配对)
                List<SimAgent> alive = pop.stream().filter(a -> a.alive).toList();
                for (SimAgent a : alive) {
                    a.age++;
                    // 产物
                    if (a.occupation != null) {
                        int per = YEARLY_OUTPUT.getOrDefault(a.occupation, 0);
                        if (per > 0) { a.products += per; totalProducts += per; a.coins += per * 3L; }
                        else a.coins += 20000L; // 普通岗位年收入
                    }
                }
                // 结婚:单身者两两随机配对,少量成婚
                List<SimAgent> singles = new ArrayList<>(pop.stream()
                        .filter(a -> a.alive && a.spouseId == 0 && a.age >= 18).toList());
                java.util.Collections.shuffle(singles);
                for (int i = 0; i + 1 < singles.size(); i += 2) {
                    if (rnd(100) >= 22) continue; // 每对每年 22% 成婚
                    SimAgent a = singles.get(i), b = singles.get(i + 1);
                    a.spouseId = b.id; b.spouseId = a.id;
                    totalMarriages++;
                    event(runId, seq, yDate, "marriage", a.id, b.id,
                            a.name + " 与 " + b.name + " 喜结连理 💍");
                }
                // 生子:已婚且育龄,低概率(遍历快照,新生儿先收集,循环后再入群,避免并发修改)
                List<SimAgent> newborns = new ArrayList<>();
                for (SimAgent a : new ArrayList<>(pop)) {
                    if (!a.alive || a.spouseId == 0 || a.age > 45 || a.age < 20) continue;
                    SimAgent sp = byId(pop, a.spouseId);
                    if (sp == null || !sp.alive || a.id > sp.id) continue; // 每对只由较小 id 触发
                    if (rnd(100) >= 30) continue; // 每对每年 30% 添丁
                    SimAgent child = new SimAgent(nextChildId--, childName(a.name), null, 0, 100, 100);
                    newborns.add(child);
                    totalBirths++;
                    event(runId, seq, yDate, "birth", a.id, sp.id,
                            a.name + " 与 " + sp.name + " 迎来了新生命 " + child.name + " 👶");
                }
                pop.addAll(newborns);
                // 死亡:按年龄 + energy
                for (SimAgent a : pop) {
                    if (!a.alive) continue;
                    int base = a.age >= 90 ? 3500 : a.age >= 80 ? 1200 : a.age >= 70 ? 400
                            : a.age >= 60 ? 120 : a.age >= 40 ? 30 : 8; // 万分之/年
                    int low = a.energy < 30 ? 800 : 0;
                    if (rnd(10000) >= base + low) continue;
                    a.alive = false;
                    if (a.spouseId != 0) { SimAgent sp = byId(pop, a.spouseId); if (sp != null) sp.spouseId = 0; }
                    totalDeaths++;
                    String cause = a.age >= 80 ? "寿终正寝" : pick("急病", "意外", "旧疾复发", "安详离世");
                    event(runId, seq, yDate, "death", a.id, null,
                            a.name + " 走完了一生(" + cause + "),享年 " + a.age + " 岁 🕯");
                }
                // 突发事件(每年至多一件)
                List<SimAgent> stillAlive = pop.stream().filter(x -> x.alive).toList();
                if (!stillAlive.isEmpty() && rnd(100) < 45) {
                    SimAgent e = stillAlive.get(rnd(stillAlive.size()));
                    String desc = randomEvent(e);
                    event(runId, seq, yDate, "event", e.id, null, desc);
                }
                // 年度里程碑
                long aliveCount = pop.stream().filter(x -> x.alive).count();
                event(runId, seq, yDate, "milestone", null, null,
                        "第 " + y + " 年:在册居民 " + aliveCount + " 人,累计作品 " + totalProducts
                                + " 件,结婚 " + totalMarriages + " 对,新生 " + totalBirths
                                + " 人,离世 " + totalDeaths + " 人。");
                // 节流:每年落库后小憩,让前端能「一年年」流式看到事件涌现(规则计算本身是毫秒级)
                pace(years);
            }

            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("years", years);
            stats.put("seeds", members.size());
            stats.put("aliveEnd", pop.stream().filter(x -> x.alive).count());
            stats.put("products", totalProducts);
            stats.put("marriages", totalMarriages);
            stats.put("births", totalBirths);
            stats.put("deaths", totalDeaths);

            String report = writeReportWithTimeout(members, years, stats, pop, tok);

            run = runRepo.findById(runId).orElse(run);
            run.setStatus("DONE");
            run.setReport(report);
            run.setActualInputTokens(tok[0]);
            run.setActualOutputTokens(tok[1]);
            run.setActualCostRmb(BigDecimal.valueOf(modelFactory.costRmb(modelFactory.defaultTextModel(), tok[0], tok[1])));
            runRepo.save(run);
            log.info("sandbox run {} DONE years={} events={} tokens={}/{}", runId, years, seq[0], tok[0], tok[1]);
        } catch (Exception ex) {
            log.warn("sandbox run {} FAILED", runId, ex);
            SandboxRun r = runRepo.findById(runId).orElse(null);
            if (r != null) {
                r.setStatus("FAILED");
                r.setErrorMsg(ex.getMessage() == null ? ex.toString() : ex.getMessage());
                runRepo.save(r);
            }
        }
    }

    private String randomEvent(SimAgent e) {
        int roll = rnd(100);
        if (roll < 25) { long w = 2000 + rnd(8000); e.coins += w; return e.name + " 在集市抽奖中了 " + w + " 金币!"; }
        if (roll < 45) { e.energy = clamp(e.energy - (10 + rnd(30)), 0, 100); return e.name + " 大病一场,元气大伤。"; }
        if (roll < 65) { e.energy = clamp(e.energy + (8 + rnd(20)), 0, 100); return e.name + " 坚持锻炼,身体愈发硬朗。"; }
        if (roll < 82) { long l = Math.min(e.coins, 500 + rnd(2000)); e.coins -= l; return e.name + " 生意受挫,损失 " + l + " 金币。"; }
        return "小镇今年办了盛大庙会," + e.name + " 成了焦点。";
    }

    /**
     * 给最终报告的 LLM 调用套一个硬超时:规则引擎已产出全部事件与统计,报告只是锦上添花。
     * GLM 账户级限流(429/1302)时,{@code streamTextWithFallback} 会在整条降级链上反复重试,
     * 可能拖住整个 run 让前端一直卡在「推演中」。超时即用统计兜底文本,保证 run 必定收敛到 DONE。
     */
    private String writeReportWithTimeout(List<AgentEmployee> members, int years,
                                          Map<String, Object> stats, List<SimAgent> pop, long[] tok) {
        String fallback = "历经 " + years + " 年,小镇几经春秋:" + stats + "。";
        java.util.concurrent.ExecutorService ex = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<String> f = ex.submit(() -> writeReport(members, years, stats, pop, tok));
            String out = f.get(45, java.util.concurrent.TimeUnit.SECONDS);
            return out == null || out.isBlank() ? fallback : out;
        } catch (java.util.concurrent.TimeoutException te) {
            log.warn("sandbox report LLM 超时,改用统计兜底文本");
            return fallback;
        } catch (Exception e) {
            log.warn("sandbox report LLM 失败,改用统计兜底文本: {}", e.getMessage());
            return fallback;
        } finally {
            ex.shutdownNow();
        }
    }

    /** 最终世界报告:把统计与结局喂 qwen-turbo 写一段叙事(唯一 LLM 调用点)。 */
    private String writeReport(List<AgentEmployee> members, int years,
                               Map<String, Object> stats, List<SimAgent> pop, long[] tok) {
        StringBuilder seeds = new StringBuilder();
        for (AgentEmployee e : members) seeds.append(e.getName())
                .append(e.getTitle() == null ? "" : "(" + e.getTitle() + ")").append("、");
        StringBuilder survivors = new StringBuilder();
        pop.stream().filter(a -> a.alive).limit(12).forEach(a ->
                survivors.append(a.name).append("(").append(a.age).append("岁)、"));
        String system = "你是智能体小镇的历史学家。请为一段跨越数年的沙盒推演写一份 5~8 句的世界报告,"
                + "语气生动、有历史纵深感,概述人口变迁、姻缘、新生与逝去、作品成就。简体中文,不要分点、不要标题、不要 markdown。";
        String user = "推演时长:" + years + " 年。起始居民:" + seeds + "\n"
                + "统计:" + stats + "\n"
                + "健在者:" + (survivors.length() == 0 ? "(无人存活到最后)" : survivors) + "\n请据此写世界报告。";
        String out = call(modelFactory.reasoningModel(), system, user, tok);
        return out == null || out.isBlank()
                ? ("历经 " + years + " 年,小镇几经春秋:" + stats + "。") : out.trim();
    }

    // ---- 工具 ----

    /** 每年节流:总时长约束在 ~8 秒内,年数越多每年停顿越短,保证流式观感又不至于太久。 */
    private void pace(int years) {
        long ms = Math.max(120, Math.min(700, 8000L / Math.max(1, years)));
        try { Thread.sleep(ms); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }

    private void event(Long runId, int[] seq, LocalDate date, String type,
                       Long agentId, Long targetId, String content) {
        SandboxEvent ev = new SandboxEvent();
        ev.setRunId(runId);
        ev.setSimDate(date);
        ev.setSeq(++seq[0]);
        ev.setType(type);
        ev.setAgentId(agentId);
        ev.setTargetAgentId(targetId);
        ev.setContent(content);
        eventRepo.save(ev);
    }

    private String call(String modelName, String system, String user, long[] tok) {
        try {
            List<Msg> messages = List.of(
                    Msg.builder().role(MsgRole.SYSTEM).content(TextBlock.builder().text(system).build()).build(),
                    Msg.builder().role(MsgRole.USER).content(TextBlock.builder().text(user).build()).build());
            List<ChatResponse> responses = modelFactory.streamTextWithFallback(modelName, messages);
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
            return ChatModelFactory.stripThink(sb.toString().trim());
        } catch (Exception ex) {
            log.warn("SandboxRunner LLM call failed", ex);
            return null;
        }
    }

    private static SimAgent byId(List<SimAgent> pop, long id) {
        for (SimAgent a : pop) if (a.id == id) return a;
        return null;
    }

    private static int ageOf(LocalDate birth, LocalDate now) {
        if (birth == null) return 30;
        int a = now.getYear() - birth.getYear();
        return Math.max(0, a);
    }

    private static String childName(String parent) {
        String s = parent == null || parent.isBlank() ? "小" : parent.substring(0, 1);
        return s + pick("小宝", "囡囡", "小豆", "阿福", "小满", "念念", "小禾", "阿岩");
    }

    private static String pick(String... opts) { return opts[ThreadLocalRandom.current().nextInt(opts.length)]; }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private static int rnd(int bound) { return bound <= 0 ? 0 : ThreadLocalRandom.current().nextInt(bound); }
}
