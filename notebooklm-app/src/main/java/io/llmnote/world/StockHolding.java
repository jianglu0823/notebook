package io.llmnote.world;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/** 持仓:每个居民每只股票一行(agent_id+code 唯一)。 */
@Data
@Entity
@Table(name = "stock_holding")
public class StockHolding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "agent_id", nullable = false)
    private Long agentId;

    @Column(nullable = false, length = 16)
    private String code;

    @Column(nullable = false)
    private Long shares = 0L;

    /** 累计买入成本(金币),用于算浮盈。 */
    @Column(nullable = false)
    private Long cost = 0L;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
