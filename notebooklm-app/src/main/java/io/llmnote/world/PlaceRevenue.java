package io.llmnote.world;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 场所营收:每日每场所一行(place_key 对齐 TownMap:restaurant/grocery/clinic/market)。 */
@Data
@Entity
@Table(name = "place_revenue")
public class PlaceRevenue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sim_date", nullable = false)
    private LocalDate simDate;

    @Column(name = "place_key", nullable = false, length = 32)
    private String placeKey;

    @Column(nullable = false)
    private Long amount = 0L;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
