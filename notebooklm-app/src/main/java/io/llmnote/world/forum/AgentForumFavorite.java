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

/** 帖子收藏:同一 user 对同一 post 只能一行(UNIQUE 约束)。 */
@Data
@Entity
@Table(name = "agent_forum_favorite",
        uniqueConstraints = @UniqueConstraint(name = "uk_fav_user_post", columnNames = {"user_id", "post_id"}),
        indexes = @Index(name = "idx_fav_post", columnList = "post_id"))
public class AgentForumFavorite {

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
