package io.llmnote.world;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一场「话题会议/群聊」:多名员工围绕 topic 轮流发言。
 * status:PENDING → RUNNING → DONE;编排失败置 FAILED。
 * events 逐句实时追加发言时间线(前端轮询围观),summary 为主持人总结。
 */
@Data
@Entity
@Table(name = "agent_meeting")
public class AgentMeeting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_id", nullable = false, length = 64)
    private String ownerId;

    @Column(nullable = false, length = 32)
    private String status = "PENDING";

    @Column(nullable = false, length = 512)
    private String topic;

    /** 参会员工 id JSON 数组 */
    @Column(name = "participant_ids", columnDefinition = "TEXT")
    private String participantIds;

    @Column(name = "max_rounds", nullable = false)
    private Integer maxRounds = 3;

    /** 发言时间线 JSON 数组(实时追加) */
    @Column(columnDefinition = "MEDIUMTEXT")
    private String events;

    /** 主持人总结 */
    @Column(columnDefinition = "MEDIUMTEXT")
    private String summary;

    /** 本场会议使用的模型 */
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
