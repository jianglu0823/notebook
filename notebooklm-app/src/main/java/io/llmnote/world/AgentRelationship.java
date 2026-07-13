package io.llmnote.world;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 居民两两关系(亲密度)。为避免 (a,b)/(b,a) 双向重复,统一归一化存储为 {@code aId < bId}。
 * 亲密度随互动累加,达阈值推进 status:stranger → friend → close → dating → married。
 */
@Data
@Entity
@Table(name = "agent_relationship",
        uniqueConstraints = @UniqueConstraint(columnNames = {"a_id", "b_id"}))
public class AgentRelationship {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归一化:aId < bId。 */
    @Column(name = "a_id", nullable = false)
    private Long aId;

    @Column(name = "b_id", nullable = false)
    private Long bId;

    /** 亲密度(0 起累加)。 */
    @Column(nullable = false)
    private Integer intimacy = 0;

    /** stranger / friend / close / dating / married。 */
    @Column(length = 16, nullable = false)
    private String status = "stranger";

    /** 累计互动次数。 */
    @Column(nullable = false)
    private Integer interactions = 0;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
