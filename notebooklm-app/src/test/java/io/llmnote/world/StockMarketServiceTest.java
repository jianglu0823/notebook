package io.llmnote.world;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StockMarketServiceTest {

    @Mock TownStockRepository stockRepo;
    @Mock StockDailyRepository stockDailyRepo;
    @Mock StockHoldingRepository holdingRepo;
    @Mock TownEconomyService economyService;
    @Mock PlaceRevenueRepository placeRevenueRepo;
    @InjectMocks StockMarketService service;

    private final LocalDate day = LocalDate.of(2026, 7, 15);

    @BeforeEach
    void seedRng() {
        service.setRng(new Random(42));
    }

    private TownStock stock(String code, long base, String sector) {
        TownStock s = new TownStock();
        s.setCode(code);
        s.setName(code + "公司");
        s.setSector(sector);
        s.setBasePrice(base);
        return s;
    }

    private AgentEmployee emp(long id, long coins) {
        AgentEmployee e = new AgentEmployee();
        e.setId(id);
        e.setCoins(coins);
        return e;
    }

    @Test
    void price_positiveAndBounded() {
        TownStock s = stock("CATER", 100, "catering");
        when(stockRepo.findAll()).thenReturn(List.of(s));
        when(stockDailyRepo.findByCodeOrderBySimDateAsc("CATER")).thenReturn(List.of());
        when(stockDailyRepo.findBySimDateAndCode(day, "CATER")).thenReturn(Optional.empty());
        when(placeRevenueRepo.findBySimDateOrderByAmountDesc(day)).thenReturn(List.of());

        service.updatePrices(day);

        ArgumentCaptor<StockDaily> cap = ArgumentCaptor.forClass(StockDaily.class);
        verify(stockDailyRepo).save(cap.capture());
        StockDaily row = cap.getValue();
        assertThat(row.getClosePrice()).isGreaterThan(0);
        double pct = row.getChangePct().doubleValue();
        assertThat(pct).isBetween(-0.20, 0.20);
    }

    @Test
    void price_onePerDayPerCode_upsert() {
        TownStock s = stock("MED", 150, "medical");
        StockDaily existing = new StockDaily();
        existing.setSimDate(day);
        existing.setCode("MED");
        when(stockRepo.findAll()).thenReturn(List.of(s));
        when(stockDailyRepo.findByCodeOrderBySimDateAsc("MED")).thenReturn(List.of());
        when(stockDailyRepo.findBySimDateAndCode(day, "MED")).thenReturn(Optional.of(existing));
        when(placeRevenueRepo.findBySimDateOrderByAmountDesc(day)).thenReturn(List.of());

        service.updatePrices(day);

        // 复用既有行(upsert),不新建
        verify(stockDailyRepo).save(same(existing));
    }

    @Test
    void buy_noOverdraft() {
        TownStock s = stock("RETAIL", 80, "retail");
        AgentEmployee e = emp(1L, 50); // 余额 50 < 单价 80,买不起
        when(stockRepo.findAll()).thenReturn(List.of(s));
        when(stockDailyRepo.findBySimDateAndCode(day, "RETAIL")).thenReturn(Optional.of(closeAt("RETAIL", 80)));

        // 强制走买入分支:用只会返回 buy 的 rng 序列较难;改为直接调用交易多轮,断言不透支
        for (int i = 0; i < 50; i++) service.residentTrade(List.of(e), day);

        assertThat(e.getCoins()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void sell_realizesPnl_andZerosHolding() {
        TownStock s = stock("ARTS", 90, "crafts");
        AgentEmployee e = emp(1L, 0);
        StockHolding h = new StockHolding();
        h.setAgentId(1L);
        h.setCode("ARTS");
        h.setShares(2L);
        h.setCost(160L); // 成本 80×2
        when(stockRepo.findAll()).thenReturn(List.of(s));
        when(stockDailyRepo.findBySimDateAndCode(day, "ARTS")).thenReturn(Optional.of(closeAt("ARTS", 100)));
        when(holdingRepo.findByAgentIdAndCode(1L, "ARTS")).thenReturn(Optional.of(h));
        when(economyService.applyDelta(eq(e), eq(day), contains("卖出"), eq(200L))).thenReturn(200L);

        // 找到会触发 sell(action==2)的 rng;用固定种子多轮直到卖出发生
        Random forceSell = new Random() {
            int n = 0;
            @Override public int nextInt(int bound) { return bound == 3 ? 2 : 0; }
            @Override public double nextDouble() { return 0.5; }
        };
        service.setRng(forceSell);
        service.residentTrade(List.of(e), day);

        assertThat(h.getShares()).isEqualTo(0);
        assertThat(h.getCost()).isEqualTo(0);
        verify(economyService).applyDelta(eq(e), eq(day), contains("卖出"), eq(200L));
    }

    @Test
    void sell_noHolding_noop() {
        TownStock s = stock("CULT", 120, "culture");
        AgentEmployee e = emp(1L, 0);
        when(stockRepo.findAll()).thenReturn(List.of(s));
        when(stockDailyRepo.findBySimDateAndCode(day, "CULT")).thenReturn(Optional.of(closeAt("CULT", 120)));
        when(holdingRepo.findByAgentIdAndCode(1L, "CULT")).thenReturn(Optional.empty());

        Random forceSell = new Random() {
            @Override public int nextInt(int bound) { return bound == 3 ? 2 : 0; }
        };
        service.setRng(forceSell);
        service.residentTrade(List.of(e), day);

        verify(economyService, never()).applyDelta(any(), any(), anyString(), anyLong());
    }

    @Test
    void buy_updatesHoldingCost() {
        TownStock s = stock("CATER", 100, "catering");
        AgentEmployee e = emp(1L, 1000); // budget=200 → 2 手
        when(stockRepo.findAll()).thenReturn(List.of(s));
        when(stockDailyRepo.findBySimDateAndCode(day, "CATER")).thenReturn(Optional.of(closeAt("CATER", 100)));
        when(holdingRepo.findByAgentIdAndCode(1L, "CATER")).thenReturn(Optional.empty());
        when(economyService.applyDelta(eq(e), eq(day), contains("买入股票"), eq(-200L))).thenReturn(-200L);

        Random forceBuy = new Random() {
            @Override public int nextInt(int bound) { return bound == 3 ? 1 : 0; }
        };
        service.setRng(forceBuy);
        service.residentTrade(List.of(e), day);

        ArgumentCaptor<StockHolding> cap = ArgumentCaptor.forClass(StockHolding.class);
        verify(holdingRepo).save(cap.capture());
        assertThat(cap.getValue().getShares()).isEqualTo(2);
        assertThat(cap.getValue().getCost()).isEqualTo(200);
    }

    private StockDaily closeAt(String code, long close) {
        StockDaily d = new StockDaily();
        d.setSimDate(day);
        d.setCode(code);
        d.setClosePrice(close);
        return d;
    }
}
