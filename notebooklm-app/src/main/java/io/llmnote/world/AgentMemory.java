package io.llmnote.world;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/** 员工的一条长期记忆/事件流记录(可观测,供前端时间线与跨员工引用)。 */
@Data
@Entity
@Table(name = "agent_memory")
public class AgentMemory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "agent_id", nullable = false)
    private Long agentId;

    /** observation / dialogue / meeting / reflection / action */
    @Column(nullable = false, length = 16)
    private String kind;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(nullable = false)
    private Integer importance = 5;

    @Column(name = "related_agent_id")
    private Long relatedAgentId;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
