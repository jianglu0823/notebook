package io.llmnote.world;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 群聊中的一条消息。role=user 为用户发言(agentId 为空);role=agent 为某居民发言(agentId 指向发言居民);
 * role=system 为系统提示(如「XX 加入了群聊」)。带 token 计费,供成本统计。
 */
@Data
@Entity
@Table(name = "agent_group_msg")
public class AgentGroupMsg {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chat_id", nullable = false)
    private Long chatId;

    /** 发言居民 id;user/system 消息为空 */
    @Column(name = "agent_id")
    private Long agentId;

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
