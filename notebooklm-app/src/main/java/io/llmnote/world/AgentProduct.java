package io.llmnote.world;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 职业产物:作家的小说章节 / 歌手的新歌 / 画师的画作等,每日结算时产出。 */
@Data
@Entity
@Table(name = "agent_product")
public class AgentProduct {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "agent_id", nullable = false)
    private Long agentId;

    /** 产出所在的小镇内在日期。 */
    @Column(name = "sim_date", nullable = false)
    private LocalDate simDate;

    @Column(length = 32)
    private String occupation;

    /** chapter / song / artwork / ... */
    @Column(length = 32)
    private String kind;

    /** 第几章/第几首(在该居民该职业内递增)。 */
    private Integer seq;

    @Column(length = 255)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String content;

    /** 质量评分 1~10。 */
    private Integer quality = 5;

    @Column(name = "input_tokens", nullable = false)
    private Long inputTokens = 0L;

    @Column(name = "output_tokens", nullable = false)
    private Long outputTokens = 0L;

    @Column(name = "cost_rmb", nullable = false)
    private BigDecimal costRmb = BigDecimal.ZERO;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
