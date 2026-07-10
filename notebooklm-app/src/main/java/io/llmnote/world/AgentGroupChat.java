package io.llmnote.world;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一场「群聊」会话:用户 + 多名居民智能体的多轮对话。区别于 {@link AgentMeeting}(一次性自动跑完的话题会议),
 * 群聊是<b>交互式</b>的——用户每发一句,当前在场的每位成员依次回应;支持中途增减成员。
 * members 存当前在场成员 id JSON 数组,消息落在 {@link AgentGroupMsg}。
 */
@Data
@Entity
@Table(name = "agent_group_chat")
public class AgentGroupChat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_id", nullable = false, length = 64)
    private String ownerId;

    @Column(nullable = false, length = 255)
    private String title;

    /** 当前在场成员 id JSON 数组(可中途增减) */
    @Column(name = "member_ids", columnDefinition = "TEXT")
    private String memberIds;

    @Column(length = 32)
    private String model;

    @Column(name = "input_tokens", nullable = false)
    private Long inputTokens = 0L;

    @Column(name = "output_tokens", nullable = false)
    private Long outputTokens = 0L;

    @Column(name = "cost_rmb", nullable = false)
    private java.math.BigDecimal costRmb = java.math.BigDecimal.ZERO;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}
