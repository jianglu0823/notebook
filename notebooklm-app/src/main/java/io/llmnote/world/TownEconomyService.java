package io.llmnote.world;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 经济核心:统一入账(applyDelta)、作品售卖收入、居民日常消费与场所归集、每日经济快照。
 * 全部规则驱动、零 LLM,供 WorldSimEngine 每日结算调用。
 */
@Service
@RequiredArgsConstructor
public class TownEconomyService {

    private final AgentEmployeeRepository employeeRepo;
    private final AgentTransactionRepository txRepo;
    private final PlaceRevenueRepository placeRevenueRepo;
    private final TownEconomyDailyRepository economyDailyRepo;

    // 消费/售卖参数(design D5/D6)
    static final long CONSUME_BASE = 80;
    static final int CONSUME_RANGE = 120;
    static final long SELL_K = 15;
    static final long SELL_BASE = 20;

    /** 消费类别权重 → TownMap place key(餐饮=restaurant,日用=grocery,医疗=clinic,娱乐=market)。 */
    private static final Map<String, Double> CONSUME_WEIGHTS = Map.of(
            "restaurant", 0.40, "grocery", 0.30, "market", 0.20, "clinic", 0.10);
    private static final Map<String, String> PLACE_LABEL = Map.of(
            "restaurant", "餐饮", "grocery", "日用", "market", "娱乐", "clinic", "医疗");

    /**
     * 给居民 e 记一笔收支并落流水。
     * @param delta 正为收入、负为支出;支出会被封顶,余额永不为负。
     * @return 实际变动额(支出被封顶时可能小于请求的绝对值)。
     */
    @Transactional
    public long applyDelta(AgentEmployee e, LocalDate date, String reason, long delta) {
        if (delta == 0) return 0L;
        long before = e.getCoins() == null ? 0L : e.getCoins();
        long applied = delta;
        if (delta < 0 && before + delta < 0) {
            applied = -before;
        }
        if (applied == 0) return 0L;
        long after = before + applied;
        e.setCoins(after);
        employeeRepo.save(e);

        AgentTransaction t = new AgentTransaction();
        t.setAgentId(e.getId());
        t.setSimDate(date);
        t.setDelta(applied);
        t.setBalance(after);
        t.setReason(reason);
        txRepo.save(t);
        return applied;
    }

    /** 作品售卖收入:round(quality*SELL_K+SELL_BASE),经 applyDelta 入账。无作品/无质量返回 0。 */
    @Transactional
    public long sellIncome(AgentEmployee e, LocalDate date, AgentProduct p) {
        if (p == null) return 0L;
        int quality = p.getQuality() == null ? 0 : p.getQuality();
        if (quality <= 0) return 0L;
        long income = quality * SELL_K + SELL_BASE;
        String title = p.getTitle() == null ? "作品" : p.getTitle();
        return applyDelta(e, date, "作品售卖:《" + title + "》", income);
    }

    /**
     * 居民当日日常消费:CONSUME_BASE+rnd(CONSUME_RANGE),按余额封顶,按类别权重拆分并逐类扣费。
     * @return 各 place key → 实际消费金额(供场所营收归集);无消费返回空 map。
     */
    @Transactional
    public Map<String, Long> consumeDaily(AgentEmployee e, LocalDate date) {
        long coins = e.getCoins() == null ? 0L : e.getCoins();
        if (coins <= 0) return new LinkedHashMap<>();
        long want = CONSUME_BASE + rnd(CONSUME_RANGE);
        long total = Math.min(coins, want);
        if (total <= 0) return new LinkedHashMap<>();

        // 按权重拆分,余数并入 restaurant,保证各类之和 == total
        Map<String, Long> plan = new LinkedHashMap<>();
        long assigned = 0;
        for (Map.Entry<String, Double> w : CONSUME_WEIGHTS.entrySet()) {
            long part = (long) Math.floor(total * w.getValue());
            plan.put(w.getKey(), part);
            assigned += part;
        }
        long remainder = total - assigned;
        plan.merge("restaurant", remainder, Long::sum);

        Map<String, Long> spent = new LinkedHashMap<>();
        for (Map.Entry<String, Long> entry : plan.entrySet()) {
            long amount = entry.getValue();
            if (amount <= 0) continue;
            long applied = applyDelta(e, date, "日常消费:" + PLACE_LABEL.get(entry.getKey()), -amount);
            long real = -applied;
            if (real > 0) spent.merge(entry.getKey(), real, Long::sum);
        }
        return spent;
    }

    /**
     * 结算某日经济:遍历活跃居民先售卖当日作品、再日常消费,把消费归集到场所营收,最后落经济快照。
     * 幂等:同 sim_date 已有快照则跳过快照写入(场所营收按 upsert)。
     */
    @Transactional
    public void settleDay(List<AgentEmployee> active, List<AgentProduct> productsToday, LocalDate date) {
        if (active == null) active = List.of();
        Map<Long, AgentEmployee> byId = new LinkedHashMap<>();
        for (AgentEmployee e : active) byId.put(e.getId(), e);

        // 1) 售卖当日作品(收入)
        if (productsToday != null) {
            for (AgentProduct p : productsToday) {
                AgentEmployee owner = byId.get(p.getAgentId());
                if (owner != null) sellIncome(owner, date, p);
            }
        }

        // 2) 日常消费 + 场所归集(支出)
        Map<String, Long> placeMap = new LinkedHashMap<>();
        for (AgentEmployee e : active) {
            Map<String, Long> spent = consumeDaily(e, date);
            for (Map.Entry<String, Long> s : spent.entrySet()) {
                placeMap.merge(s.getKey(), s.getValue(), Long::sum);
            }
        }

        // 3) 场所营收落库(idempotent upsert)
        long totalPlaceRevenue = 0;
        for (Map.Entry<String, Long> pr : placeMap.entrySet()) {
            long amount = pr.getValue();
            totalPlaceRevenue += amount;
            PlaceRevenue row = placeRevenueRepo.findBySimDateAndPlaceKey(date, pr.getKey())
                    .orElseGet(() -> {
                        PlaceRevenue x = new PlaceRevenue();
                        x.setSimDate(date);
                        x.setPlaceKey(pr.getKey());
                        return x;
                    });
            row.setAmount(amount);
            placeRevenueRepo.save(row);
        }

        // 4) 经济快照(幂等:已有则跳过)
        if (!economyDailyRepo.existsById(date)) {
            long totalCoins = 0;
            for (AgentEmployee e : active) totalCoins += (e.getCoins() == null ? 0L : e.getCoins());
            TownEconomyDaily snap = new TownEconomyDaily();
            snap.setSimDate(date);
            snap.setTotalCoins(totalCoins);
            snap.setTotalIncome(txRepo.sumIncomeByDate(date));
            snap.setTotalExpense(txRepo.sumExpenseByDate(date));
            snap.setTotalPlaceRevenue(totalPlaceRevenue);
            economyDailyRepo.save(snap);
        }
    }

    private static int rnd(int bound) { return bound <= 0 ? 0 : ThreadLocalRandom.current().nextInt(bound); }
}
