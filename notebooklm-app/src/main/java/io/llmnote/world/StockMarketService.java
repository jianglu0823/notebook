package io.llmnote.world;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 股市:有界随机游走行情(挂钩场所营收)+ 居民规则化炒股。全部零 LLM。
 * 供 WorldSimEngine 每日结算调用:先 updatePrices,再 residentTrade。
 */
@Service
@RequiredArgsConstructor
public class StockMarketService {

    private final TownStockRepository stockRepo;
    private final StockDailyRepository stockDailyRepo;
    private final StockHoldingRepository holdingRepo;
    private final TownEconomyService economyService;
    private final PlaceRevenueRepository placeRevenueRepo;

    /** 单日涨跌幅上限(±20%)。 */
    static final double MAX_PCT = 0.20;
    /** 场所营收对板块的信号强度(与 ±8% 噪声相比要小得多,避免单边漂移)。 */
    private static final double SECTOR_GAIN = 0.015;
    /** 触发场所正信号的营收阈值(按单场所日均营收量级设定,正常营收不给正拉动)。 */
    private static final long SECTOR_REVENUE_PIVOT = 3500;

    /** 板块 → TownMap place key(用当日营收派生行情信号)。 */
    private static final Map<String, String> SECTOR_PLACE = Map.of(
            "catering", "restaurant",
            "medical", "clinic",
            "retail", "grocery",
            "culture", "market");

    /** 可注入随机源,便于测试。 */
    private Random rng = new Random();

    void setRng(Random rng) { this.rng = rng; }

    /**
     * 更新某日全部股票行情:close=prev*(1+clamp(drift+noise+sectorSignal,±MAX)),幂等(同 sim_date+code 只一条)。
     */
    @Transactional
    public void updatePrices(LocalDate date) {
        List<TownStock> stocks = stockRepo.findAll();
        Map<String, Long> placeRev = placeRevenueOf(date);
        for (TownStock s : stocks) {
            long prev = lastClose(s);
            double drift = 0.0;
            double noise = (rng.nextDouble() - 0.5) * 0.16; // ±8%
            double sectorSignal = sectorSignal(s.getSector(), placeRev);
            double pct = clamp(drift + noise + sectorSignal, -MAX_PCT, MAX_PCT);
            long close = Math.max(1L, Math.round(prev * (1 + pct)));

            StockDaily row = stockDailyRepo.findBySimDateAndCode(date, s.getCode())
                    .orElseGet(() -> {
                        StockDaily x = new StockDaily();
                        x.setSimDate(date);
                        x.setCode(s.getCode());
                        return x;
                    });
            row.setOpenPrice(prev);
            row.setClosePrice(close);
            row.setChangePct(BigDecimal.valueOf(pct).setScale(4, RoundingMode.HALF_UP));
            stockDailyRepo.save(row);
        }
    }

    /**
     * 居民规则化炒股:每人按余额比例 + 简单倾向决定买/卖/持有;不透支、不超卖。
     */
    @Transactional
    public void residentTrade(List<AgentEmployee> active, LocalDate date) {
        if (active == null || active.isEmpty()) return;
        List<TownStock> stocks = stockRepo.findAll();
        if (stocks.isEmpty()) return;

        for (AgentEmployee e : active) {
            TownStock pick = stocks.get(rng.nextInt(stocks.size()));
            long price = todayClose(pick.getCode(), date);
            if (price <= 0) continue;
            int action = rng.nextInt(3); // 0 持有 1 买 2 卖
            if (action == 1) {
                tryBuy(e, pick, price, date);
            } else if (action == 2) {
                trySell(e, pick, price, date);
            }
        }
    }

    /** 每日股市结算:先更新行情,再居民交易。 */
    @Transactional
    public void settleDay(List<AgentEmployee> active, LocalDate date) {
        updatePrices(date);
        residentTrade(active, date);
    }

    // ---- 内部 ----

    private void tryBuy(AgentEmployee e, TownStock stock, long price, LocalDate date) {
        long coins = e.getCoins() == null ? 0L : e.getCoins();
        long budget = coins / 5; // 最多花 20% 余额
        long shares = budget / price;
        if (shares <= 0) return;
        long cost = shares * price;
        long applied = economyService.applyDelta(e, date, "买入股票:" + stock.getName(), -cost);
        if (applied == 0) return;
        long realShares = (-applied) / price;
        if (realShares <= 0) return;
        long realCost = realShares * price;
        // 若封顶导致少买,退回多扣的零头
        if (realCost < -applied) {
            economyService.applyDelta(e, date, "买入找零:" + stock.getName(), (-applied) - realCost);
        }
        StockHolding h = holdingRepo.findByAgentIdAndCode(e.getId(), stock.getCode())
                .orElseGet(() -> {
                    StockHolding x = new StockHolding();
                    x.setAgentId(e.getId());
                    x.setCode(stock.getCode());
                    x.setShares(0L);
                    x.setCost(0L);
                    return x;
                });
        h.setShares(h.getShares() + realShares);
        h.setCost(h.getCost() + realCost);
        holdingRepo.save(h);
    }

    private void trySell(AgentEmployee e, TownStock stock, long price, LocalDate date) {
        StockHolding h = holdingRepo.findByAgentIdAndCode(e.getId(), stock.getCode()).orElse(null);
        if (h == null || h.getShares() <= 0) return;
        long shares = h.getShares(); // 全卖(规则简单)
        long proceeds = shares * price;
        // 卖出成本按加权比例结转(全卖即全部成本)
        h.setShares(0L);
        h.setCost(0L);
        holdingRepo.save(h);
        economyService.applyDelta(e, date, "卖出股票:" + stock.getName(), proceeds);
    }

    private long lastClose(TownStock s) {
        List<StockDaily> hist = stockDailyRepo.findByCodeOrderBySimDateAsc(s.getCode());
        if (hist == null || hist.isEmpty()) return s.getBasePrice() == null ? 100L : s.getBasePrice();
        return hist.get(hist.size() - 1).getClosePrice();
    }

    private long todayClose(String code, LocalDate date) {
        return stockDailyRepo.findBySimDateAndCode(date, code)
                .map(StockDaily::getClosePrice).orElse(0L);
    }

    private double sectorSignal(String sector, Map<String, Long> placeRev) {
        if (sector == null) return 0.0;
        String place = SECTOR_PLACE.get(sector);
        if (place == null) return 0.0;
        long rev = placeRev.getOrDefault(place, 0L);
        return rev >= SECTOR_REVENUE_PIVOT ? SECTOR_GAIN : -SECTOR_GAIN / 2;
    }

    private Map<String, Long> placeRevenueOf(LocalDate date) {
        java.util.Map<String, Long> m = new java.util.HashMap<>();
        for (PlaceRevenue pr : placeRevenueRepo.findBySimDateOrderByAmountDesc(date)) {
            m.merge(pr.getPlaceKey(), pr.getAmount(), Long::sum);
        }
        return m;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
