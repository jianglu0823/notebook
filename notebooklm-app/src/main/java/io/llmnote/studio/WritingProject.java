package io.llmnote.studio;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 多智能体协同写作项目:作者⇄编辑⇄核查 三个 ReActAgent 迭代收敛。
 * status:PENDING → RUNNING → DONE;编排失败置 FAILED。
 * rounds 记录每轮协作过程(draft/review/factcheck/verdict),供前端「围观」。
 */
@Data
@Entity
@Table(name = "writing_project")
public class WritingProject {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_id", nullable = false, length = 64)
    private String ownerId;

    @Column(nullable = false, length = 32)
    private String status = "PENDING";

    /** 写作主题/需求 */
    @Column(nullable = false, length = 512)
    private String topic;

    /** 体裁:ARTICLE/STORY/REVIEW/SCRIPT */
    @Column(length = 32)
    private String genre;

    @Column(name = "max_rounds", nullable = false)
    private Integer maxRounds = 3;

    /** 每轮协作过程 JSON 数组 */
    @Column(columnDefinition = "MEDIUMTEXT")
    private String rounds;

    /** 协作事件时间线 JSON 数组(节点/思考/工具调用实时追加,前端轮询围观) */
    @Column(columnDefinition = "MEDIUMTEXT")
    private String events;

    /** 收敛后的终稿 */
    @Column(name = "final_text", columnDefinition = "MEDIUMTEXT")
    private String finalText;

    /** 编辑是否 APPROVE 收敛 */
    @Column(nullable = false)
    private Boolean approved = false;

    /** 本次协作使用的模型 */
    @Column(length = 32)
    private String model;

    @Column(name = "input_tokens", nullable = false)
    private Long inputTokens = 0L;

    @Column(name = "output_tokens", nullable = false)
    private Long outputTokens = 0L;

    /** 按模型单价换算的费用(元) */
    @Column(name = "cost_rmb", nullable = false)
    private java.math.BigDecimal costRmb = java.math.BigDecimal.ZERO;

    @Column(name = "error_msg", length = 2048)
    private String errorMsg;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}
