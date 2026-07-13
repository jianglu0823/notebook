package io.llmnote.world;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 一次「沙盒快进」任务:选定居民 + 快进年数,在隔离的规则引擎中模拟若干年,
 * 只在里程碑/最终报告处少量调用 LLM 写叙事,<b>完全不改真实世界的任何表</b>。
 *
 * <p>每人限一次(按 {@link #ownerId} 去重),管理员不限。运行产物:{@link #report} 世界报告 +
 * {@link SandboxEvent} 时间线(供前端回放)。token/花费仅拼给管理者看,不进任何 LLM 提示。
 */
@Data
@Entity
@Table(name = "sandbox_run")
public class SandboxRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 发起人:u:&lt;id&gt; / g:&lt;uuid&gt;。 */
    @Column(name = "owner_id", nullable = false, length = 64)
    private String ownerId;

    @Column(length = 255)
    private String title;

    /** 快进年数。 */
    @Column(nullable = false)
    private Integer years;

    /** 参与居民 id 列表(JSON 数组)。 */
    @Column(name = "member_ids", columnDefinition = "TEXT")
    private String memberIds;

    /** PENDING / RUNNING / DONE / FAILED。 */
    @Column(length = 16, nullable = false)
    private String status = "PENDING";

    @Column(name = "est_cost_rmb", precision = 12, scale = 6)
    private BigDecimal estCostRmb;

    @Column(name = "actual_input_tokens")
    private Long actualInputTokens;

    @Column(name = "actual_output_tokens")
    private Long actualOutputTokens;

    @Column(name = "actual_cost_rmb", precision = 12, scale = 6)
    private BigDecimal actualCostRmb;

    /** 世界报告(LLM 叙事)。 */
    @Column(columnDefinition = "MEDIUMTEXT")
    private String report;

    @Column(name = "error_msg", length = 512)
    private String errorMsg;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}
