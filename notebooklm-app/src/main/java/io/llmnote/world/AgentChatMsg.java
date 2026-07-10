package io.llmnote.world;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 用户与某员工的 1:1 对话消息。role=user 为用户输入,role=agent 为员工回复(带 token 计费)。 */
@Data
@Entity
@Table(name = "agent_chat_msg")
public class AgentChatMsg {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "agent_id", nullable = false)
    private Long agentId;

    @Column(name = "owner_id", nullable = false, length = 64)
    private String ownerId;

    @Column(nullable = false, length = 8)
    private String role;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "input_tokens", nullable = false)
    private Long inputTokens = 0L;

    @Column(name = "output_tokens", nullable = false)
    private Long outputTokens = 0L;

    @Column(name = "cost_rmb", nullable = false)
    private BigDecimal costRmb = BigDecimal.ZERO;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
