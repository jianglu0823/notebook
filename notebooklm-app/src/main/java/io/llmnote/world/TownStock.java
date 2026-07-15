package io.llmnote.world;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/** 股票定义(种子数据,code 为主键)。 */
@Data
@Entity
@Table(name = "town_stock")
public class TownStock {

    @Id
    @Column(length = 16)
    private String code;

    @Column(nullable = false, length = 64)
    private String name;

    @Column(length = 32)
    private String sector;

    @Column(name = "base_price", nullable = false)
    private Long basePrice = 100L;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
