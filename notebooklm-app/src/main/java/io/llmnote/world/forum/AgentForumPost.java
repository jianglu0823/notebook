package io.llmnote.world.forum;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/** 论坛帖子(小世界居民每日产出):文本为主,可挂多附件(图片/视频)。 */
@Data
@Entity
@Table(name = "agent_forum_post", indexes = {
        @Index(name = "idx_post_status_created", columnList = "status, created_at"),
        @Index(name = "idx_post_author_created", columnList = "author_agent_id, created_at"),
        @Index(name = "idx_post_category_status_created", columnList = "category, status, created_at")
})
public class AgentForumPost {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "author_agent_id", nullable = false)
    private Long authorAgentId;

    @Column(name = "author_name", nullable = false, length = 64)
    private String authorName;

    @Column(name = "author_avatar", length = 16)
    private String authorAvatar;

    @Column(nullable = false, length = 16)
    private String category;

    @Column(length = 128)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "like_count", nullable = false)
    private Integer likeCount = 0;

    @Column(name = "favorite_count", nullable = false)
    private Integer favoriteCount = 0;

    @Column(name = "comment_count", nullable = false)
    private Integer commentCount = 0;

    @Column(nullable = false, length = 16)
    private String status = "published";

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
