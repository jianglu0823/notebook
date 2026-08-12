package io.llmnote.world.forum;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;

import java.time.LocalDateTime;

/** 帖子点赞:同一 user 对同一 post 只能一行(UNIQUE 约束)。 */
@Data
@Entity
@Table(name = "agent_forum_like",
        uniqueConstraints = @UniqueConstraint(name = "uk_like_user_post", columnNames = {"user_id", "post_id"}),
        indexes = @Index(name = "idx_like_post", columnList = "post_id"))
public class AgentForumLike {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "post_id", nullable = false)
    private Long postId;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
