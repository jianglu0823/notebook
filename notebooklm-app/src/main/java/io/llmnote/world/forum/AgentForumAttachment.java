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

/** 论坛帖子附件(图 / 视频),一对多挂在 AgentForumPost 下。 */
@Data
@Entity
@Table(name = "agent_forum_attachment", indexes = {
        @Index(name = "idx_attach_post_seq", columnList = "post_id, seq")
})
public class AgentForumAttachment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "post_id", nullable = false)
    private Long postId;

    @Column(name = "media_type", nullable = false, length = 8)
    private String mediaType;

    @Column(name = "file_path", nullable = false, length = 255)
    private String filePath;

    @Column(nullable = false)
    private Integer seq;

    @Column(columnDefinition = "TEXT")
    private String prompt;

    private Integer width;
    private Integer height;
    @Column(name = "duration_sec")
    private Integer durationSec;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
