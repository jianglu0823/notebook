package io.llmnote.world;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 员工一次自主行动记录(移动/思考/找人说话/反思),带 token 计费。 */
@Data
@Entity
@Table(name = "agent_action")
public class AgentAction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "agent_id", nullable = false)
    private Long agentId;

    /** move / think / talk / reflect / goto */
    @Column(nullable = false, length = 16)
    private String type;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(name = "target_agent_id")
    private Long targetAgentId;

    /** 该次行动发生地点 key(见 {@link TownMap})。 */
    @Column(length = 32)
    private String place;

    /** 场景短标题(≤8字,如「在咖啡馆闲聊」),可空。 */
    @Column(length = 64)
    private String scene;

    @Column(name = "input_tokens", nullable = false)
    private Long inputTokens = 0L;

    @Column(name = "output_tokens", nullable = false)
    private Long outputTokens = 0L;

    @Column(name = "cost_rmb", nullable = false)
    private BigDecimal costRmb = BigDecimal.ZERO;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
