package io.llmnote.world;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 世界日报:一天一行。天气/季节 + 结构化统计 + LLM 写的当日叙事 + 当日全世界 token/花费。
 * token/花费仅拼给管理者看,<b>不进任何居民 prompt</b>。
 */
@Data
@Entity
@Table(name = "world_daily_report")
public class WorldDailyReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sim_date", nullable = false, unique = true)
    private LocalDate simDate;

    @Column(length = 8)
    private String season;

    @Column(length = 16)
    private String weather;

    /** 当日温度(℃,可空=未知,兼容旧日报)。 */
    @Column(name = "temperature")
    private Integer temperature;

    /** 杰出成就 JSON 数组。 */
    @Column(name = "highlights_json", columnDefinition = "TEXT")
    private String highlightsJson;

    /** 统计 JSON:新章数/新歌/出生/死亡/结婚数等。 */
    @Column(name = "stats_json", columnDefinition = "TEXT")
    private String statsJson;

    /** 突发新闻列表 JSON。 */
    @Column(name = "news_json", columnDefinition = "TEXT")
    private String newsJson;

    /** LLM 写的当日叙事。 */
    @Column(columnDefinition = "TEXT")
    private String narrative;

    @Column(name = "total_input_tokens", nullable = false)
    private Long totalInputTokens = 0L;

    @Column(name = "total_output_tokens", nullable = false)
    private Long totalOutputTokens = 0L;

    @Column(name = "total_cost_rmb", nullable = false)
    private BigDecimal totalCostRmb = BigDecimal.ZERO;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
