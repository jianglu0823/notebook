package io.llmnote.world;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 每日行情:每股每日一行(sim_date+code 唯一)。 */
@Data
@Entity
@Table(name = "stock_daily")
public class StockDaily {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sim_date", nullable = false)
    private LocalDate simDate;

    @Column(nullable = false, length = 16)
    private String code;

    @Column(name = "open_price", nullable = false)
    private Long openPrice = 0L;

    @Column(name = "close_price", nullable = false)
    private Long closePrice = 0L;

    /** 当日涨跌幅(小数,如 0.0800=+8%)。 */
    @Column(name = "change_pct", nullable = false, precision = 6, scale = 4)
    private BigDecimal changePct = BigDecimal.ZERO;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
