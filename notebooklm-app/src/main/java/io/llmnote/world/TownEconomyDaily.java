package io.llmnote.world;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 小镇每日经济快照:一天一行(PK=sim_date 天然幂等)。 */
@Data
@Entity
@Table(name = "town_economy_daily")
public class TownEconomyDaily {

    @Id
    @Column(name = "sim_date")
    private LocalDate simDate;

    @Column(name = "total_coins", nullable = false)
    private Long totalCoins = 0L;

    @Column(name = "total_income", nullable = false)
    private Long totalIncome = 0L;

    @Column(name = "total_expense", nullable = false)
    private Long totalExpense = 0L;

    @Column(name = "total_place_revenue", nullable = false)
    private Long totalPlaceRevenue = 0L;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
