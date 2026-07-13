package io.llmnote.world;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 沙盒时间线上的一条事件,供前端按 {@link #seq} 步进回放。
 * 由 {@link SandboxRunner} 在规则模拟中产出:出生/死亡/结婚/恋爱/产物/突发事件/里程碑。
 */
@Data
@Entity
@Table(name = "sandbox_event")
public class SandboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Column(name = "sim_date")
    private LocalDate simDate;

    /** 回放步序(自增)。 */
    @Column(nullable = false)
    private Integer seq;

    /** birth/death/marriage/dating/product/event/milestone。 */
    @Column(length = 24, nullable = false)
    private String type;

    @Column(name = "agent_id")
    private Long agentId;

    @Column(name = "target_agent_id")
    private Long targetAgentId;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(name = "meta_json", columnDefinition = "TEXT")
    private String metaJson;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
