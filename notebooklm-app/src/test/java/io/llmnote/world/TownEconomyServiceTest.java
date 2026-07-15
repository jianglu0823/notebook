package io.llmnote.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TownEconomyServiceTest {

    @Mock AgentEmployeeRepository employeeRepo;
    @Mock AgentTransactionRepository txRepo;
    @Mock PlaceRevenueRepository placeRevenueRepo;
    @Mock TownEconomyDailyRepository economyDailyRepo;
    @InjectMocks TownEconomyService service;

    private final LocalDate day = LocalDate.of(2026, 7, 15);

    private AgentEmployee emp(long coins) {
        return emp(1L, coins);
    }

    private AgentEmployee emp(long id, long coins) {
        AgentEmployee e = new AgentEmployee();
        e.setId(id);
        e.setCoins(coins);
        return e;
    }

    private AgentProduct product(long agentId, int quality, String title) {
        AgentProduct p = new AgentProduct();
        p.setAgentId(agentId);
        p.setQuality(quality);
        p.setTitle(title);
        return p;
    }

    @Test
    void income_addsToCoins_andRecordsFullLedger() {
        AgentEmployee e = emp(100);
        long applied = service.applyDelta(e, day, "工资", 50);

        assertThat(applied).isEqualTo(50);
        assertThat(e.getCoins()).isEqualTo(150);
        ArgumentCaptor<AgentTransaction> cap = ArgumentCaptor.forClass(AgentTransaction.class);
        verify(txRepo).save(cap.capture());
        assertThat(cap.getValue().getDelta()).isEqualTo(50);
        assertThat(cap.getValue().getBalance()).isEqualTo(150);
    }

    @Test
    void smallAmount_stillRecorded_noThreshold() {
        AgentEmployee e = emp(0);
        service.applyDelta(e, day, "小额", 5);
        verify(txRepo, times(1)).save(any());
    }

    @Test
    void expense_deductsCoins() {
        AgentEmployee e = emp(200);
        long applied = service.applyDelta(e, day, "消费", -80);
        assertThat(applied).isEqualTo(-80);
        assertThat(e.getCoins()).isEqualTo(120);
    }

    @Test
    void expense_cappedSoCoinsNeverNegative() {
        AgentEmployee e = emp(30);
        long applied = service.applyDelta(e, day, "消费", -100);
        assertThat(applied).isEqualTo(-30);
        assertThat(e.getCoins()).isEqualTo(0);
    }

    @Test
    void zeroDelta_noop() {
        AgentEmployee e = emp(50);
        long applied = service.applyDelta(e, day, "无", 0);
        assertThat(applied).isEqualTo(0);
        verify(txRepo, never()).save(any());
        verify(employeeRepo, never()).save(any());
    }

    @Test
    void expenseAtExactBalance_zerosOut_andRecords() {
        AgentEmployee e = emp(80);
        long applied = service.applyDelta(e, day, "消费", -80);
        assertThat(applied).isEqualTo(-80);
        assertThat(e.getCoins()).isEqualTo(0);
        verify(txRepo).save(any());
    }

    // ---- B2.1 售卖 ----

    @Test
    void sellProduct_incomePositiveWithQuality() {
        AgentEmployee hi = emp(1L, 0);
        long incomeHi = service.sellIncome(hi, day, product(1L, 9, "画作"));
        AgentEmployee lo = emp(2L, 0);
        long incomeLo = service.sellIncome(lo, day, product(2L, 3, "小画"));
        assertThat(incomeHi).isGreaterThan(incomeLo);
        assertThat(incomeHi).isEqualTo(9 * 15 + 20);
    }

    @Test
    void sellProduct_nullOrZeroQuality_noIncome() {
        AgentEmployee e = emp(0);
        assertThat(service.sellIncome(e, day, product(1L, 0, "无质量"))).isEqualTo(0);
        assertThat(service.sellIncome(e, day, null)).isEqualTo(0);
        verify(txRepo, never()).save(any());
    }

    // ---- B2.2 消费 ----

    @Test
    void consume_notOverdraft() {
        AgentEmployee e = emp(30);
        Map<String, Long> spent = service.consumeDaily(e, day);
        assertThat(e.getCoins()).isEqualTo(0);
        long sum = spent.values().stream().mapToLong(Long::longValue).sum();
        assertThat(sum).isEqualTo(30);
    }

    @Test
    void consume_splitToPlaces_sumEqualsTotal() {
        AgentEmployee e = emp(10_000); // 充足余额,消费全额落地
        Map<String, Long> spent = service.consumeDaily(e, day);
        long spentSum = spent.values().stream().mapToLong(Long::longValue).sum();
        long deducted = 10_000 - e.getCoins();
        assertThat(spentSum).isEqualTo(deducted);
        assertThat(spent.keySet()).containsAnyOf("restaurant", "grocery", "market", "clinic");
        assertThat(spent.keySet()).isSubsetOf("restaurant", "grocery", "market", "clinic");
    }

    @Test
    void consume_zeroCoins_noSpend() {
        AgentEmployee e = emp(0);
        Map<String, Long> spent = service.consumeDaily(e, day);
        assertThat(spent).isEmpty();
        verify(txRepo, never()).save(any());
    }

    // ---- B2.3 settleDay ----

    @Test
    void settleDay_conservation_and_snapshot() {
        AgentEmployee a = emp(1L, 5_000);
        AgentEmployee b = emp(2L, 5_000);
        List<AgentEmployee> active = List.of(a, b);

        when(placeRevenueRepo.findBySimDateAndPlaceKey(eq(day), anyString())).thenReturn(Optional.empty());
        when(economyDailyRepo.existsById(day)).thenReturn(false);
        when(txRepo.sumIncomeByDate(day)).thenReturn(0L);
        when(txRepo.sumExpenseByDate(day)).thenReturn(0L);

        service.settleDay(active, List.of(), day);

        ArgumentCaptor<PlaceRevenue> prCap = ArgumentCaptor.forClass(PlaceRevenue.class);
        verify(placeRevenueRepo, atLeastOnce()).save(prCap.capture());
        long placeSum = prCap.getAllValues().stream().mapToLong(PlaceRevenue::getAmount).sum();
        long deducted = (5_000 - a.getCoins()) + (5_000 - b.getCoins());
        assertThat(placeSum).isEqualTo(deducted);

        ArgumentCaptor<TownEconomyDaily> snapCap = ArgumentCaptor.forClass(TownEconomyDaily.class);
        verify(economyDailyRepo).save(snapCap.capture());
        assertThat(snapCap.getValue().getTotalCoins()).isEqualTo(a.getCoins() + b.getCoins());
    }

    @Test
    void settleDay_snapshotIdempotent() {
        AgentEmployee a = emp(1L, 5_000);
        when(placeRevenueRepo.findBySimDateAndPlaceKey(eq(day), anyString())).thenReturn(Optional.empty());
        when(economyDailyRepo.existsById(day)).thenReturn(true);

        service.settleDay(List.of(a), List.of(), day);

        verify(economyDailyRepo, never()).save(any());
    }
}
