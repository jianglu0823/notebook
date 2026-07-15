package io.llmnote.world;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 小镇经济与金融只读 API。数据全局共享(owner="world"),前端经济面板消费。
 * 全部只读,不改状态;空库返回空态而非 500。
 */
@RestController
@RequestMapping("/api/world")
@RequiredArgsConstructor
public class EconomyController {

    private final TownEconomyDailyRepository economyDailyRepo;
    private final PlaceRevenueRepository placeRevenueRepo;
    private final AgentEmployeeRepository employeeRepo;
    private final AgentTransactionRepository txRepo;
    private final TownStockRepository stockRepo;
    private final StockDailyRepository stockDailyRepo;
    private final StockHoldingRepository holdingRepo;

    /** 场所 key → 中文名(前端展示)。 */
    private static final Map<String, String> PLACE_LABEL = Map.of(
            "restaurant", "食肆", "grocery", "杂货铺", "clinic", "回春医馆", "market", "集市");

    /** 最新经济快照 + 近 30 日序列。 */
    @GetMapping("/economy/summary")
    public Map<String, Object> summary() {
        Map<String, Object> out = new LinkedHashMap<>();
        TownEconomyDaily latest = economyDailyRepo.findTopByOrderBySimDateDesc().orElse(null);
        out.put("latest", latest);
        List<TownEconomyDaily> series = new ArrayList<>(economyDailyRepo.findTop30ByOrderBySimDateDesc());
        series.sort(Comparator.comparing(TownEconomyDaily::getSimDate));
        out.put("series", series);
        return out;
    }

    /** 场所营收:指定日期(缺省取最新有数据的一天)。 */
    @GetMapping("/economy/places")
    public Map<String, Object> places(@RequestParam(required = false) String date) {
        LocalDate day = resolveDay(date);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("date", day);
        List<Map<String, Object>> rows = new ArrayList<>();
        if (day != null) {
            for (PlaceRevenue pr : placeRevenueRepo.findBySimDateOrderByAmountDesc(day)) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("placeKey", pr.getPlaceKey());
                r.put("name", PLACE_LABEL.getOrDefault(pr.getPlaceKey(), pr.getPlaceKey()));
                r.put("amount", pr.getAmount());
                rows.add(r);
            }
        }
        out.put("places", rows);
        return out;
    }

    /** 富豪榜:活跃居民按金币降序取 top N(默认 10)。 */
    @GetMapping("/economy/rich")
    public List<Map<String, Object>> rich(@RequestParam(defaultValue = "10") int limit) {
        List<AgentEmployee> active = new ArrayList<>(employeeRepo.findByStatusOrderByIdAsc("active"));
        active.sort(Comparator.comparingLong((AgentEmployee e) -> e.getCoins() == null ? 0L : e.getCoins()).reversed());
        int n = Math.min(Math.max(1, limit), active.size());
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            AgentEmployee e = active.get(i);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", e.getId());
            r.put("name", e.getName());
            r.put("occupation", e.getOccupation());
            r.put("coins", e.getCoins() == null ? 0L : e.getCoins());
            r.put("netWorth", netWorth(e));
            out.add(r);
        }
        return out;
    }

    /** 居民日/月净收入 + 明细(range=day 默认;month 返回按月汇总)。 */
    @GetMapping("/agents/{id}/income")
    public Map<String, Object> income(@PathVariable Long id,
                                      @RequestParam(defaultValue = "day") String range) {
        AgentEmployee e = employeeRepo.findById(id).orElse(null);
        Map<String, Object> out = new LinkedHashMap<>();
        if (e == null) {
            out.put("found", false);
            return out;
        }
        out.put("found", true);
        out.put("id", id);
        out.put("coins", e.getCoins() == null ? 0L : e.getCoins());
        out.put("netWorth", netWorth(e));
        if ("month".equalsIgnoreCase(range)) {
            List<Map<String, Object>> months = new ArrayList<>();
            for (Object[] row : txRepo.sumDeltaByAgentGroupByMonth(id)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("year", ((Number) row[0]).intValue());
                m.put("month", ((Number) row[1]).intValue());
                m.put("net", ((Number) row[2]).longValue());
                months.add(m);
            }
            out.put("range", "month");
            out.put("months", months);
        } else {
            LocalDate day = resolveDay(null);
            out.put("range", "day");
            out.put("date", day);
            out.put("net", day == null ? 0L : txRepo.sumDeltaByAgentAndDate(id, day));
            List<AgentTransaction> detail = day == null ? List.of()
                    : txRepo.findByAgentIdAndSimDateOrderByIdAsc(id, day);
            out.put("detail", detail);
        }
        return out;
    }

    /** 股市行情:每只股票 + 近 60 日序列。 */
    @GetMapping("/stocks")
    public List<Map<String, Object>> stocks() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (TownStock s : stockRepo.findAll()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("code", s.getCode());
            r.put("name", s.getName());
            r.put("sector", s.getSector());
            List<StockDaily> hist = stockDailyRepo.findByCodeOrderBySimDateAsc(s.getCode());
            long price = hist.isEmpty() ? (s.getBasePrice() == null ? 100L : s.getBasePrice())
                    : hist.get(hist.size() - 1).getClosePrice();
            r.put("price", price);
            if (!hist.isEmpty()) {
                r.put("changePct", hist.get(hist.size() - 1).getChangePct());
            }
            int from = Math.max(0, hist.size() - 60);
            List<Map<String, Object>> series = new ArrayList<>();
            for (StockDaily d : hist.subList(from, hist.size())) {
                Map<String, Object> point = new LinkedHashMap<>();
                point.put("date", d.getSimDate());
                point.put("close", d.getClosePrice());
                series.add(point);
            }
            r.put("series", series);
            out.add(r);
        }
        return out;
    }

    /** 居民持仓 + 浮盈((现价-均价)*股数)。 */
    @GetMapping("/agents/{id}/holdings")
    public List<Map<String, Object>> holdings(@PathVariable Long id) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (StockHolding h : holdingRepo.findByAgentId(id)) {
            if (h.getShares() == null || h.getShares() <= 0) continue;
            long price = currentPrice(h.getCode());
            long marketValue = price * h.getShares();
            long pnl = marketValue - (h.getCost() == null ? 0L : h.getCost());
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("code", h.getCode());
            r.put("name", stockRepo.findById(h.getCode()).map(TownStock::getName).orElse(h.getCode()));
            r.put("shares", h.getShares());
            r.put("cost", h.getCost());
            r.put("price", price);
            r.put("marketValue", marketValue);
            r.put("pnl", pnl);
            out.add(r);
        }
        return out;
    }

    // ---- 内部 ----

    /** 净资产 = 金币 + 持仓市值。 */
    private long netWorth(AgentEmployee e) {
        long coins = e.getCoins() == null ? 0L : e.getCoins();
        long holdingsValue = 0;
        for (StockHolding h : holdingRepo.findByAgentId(e.getId())) {
            if (h.getShares() == null || h.getShares() <= 0) continue;
            holdingsValue += currentPrice(h.getCode()) * h.getShares();
        }
        return coins + holdingsValue;
    }

    private long currentPrice(String code) {
        List<StockDaily> hist = stockDailyRepo.findByCodeOrderBySimDateAsc(code);
        if (!hist.isEmpty()) return hist.get(hist.size() - 1).getClosePrice();
        return stockRepo.findById(code).map(s -> s.getBasePrice() == null ? 100L : s.getBasePrice()).orElse(100L);
    }

    /** 解析日期参数;缺省用最新经济快照日期。 */
    private LocalDate resolveDay(String date) {
        if (date != null && !date.isBlank()) {
            try { return LocalDate.parse(date.trim()); } catch (Exception ignore) { }
        }
        return economyDailyRepo.findTopByOrderBySimDateDesc().map(TownEconomyDaily::getSimDate).orElse(null);
    }
}
