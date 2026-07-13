package io.llmnote.world;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/** 自由评价:游客/用户对某个作品(product)或某位居民(agent)发表的评论。 */
@Data
@Entity
@Table(name = "agent_comment")
public class AgentComment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 评论对象类型:product(作品) / agent(居民)。 */
    @Column(name = "target_type", length = 16, nullable = false)
    private String targetType;

    @Column(name = "target_id", nullable = false)
    private Long targetId;

    /** 发表者 ownerId(u:&lt;id&gt; / g:&lt;uuid&gt;),用于删除权限判断。 */
    @Column(name = "author_id", length = 64)
    private String authorId;

    /** 发表者展示名(用户名 / 游客),落库时快照,免每次回查。 */
    @Column(name = "author_name", length = 64)
    private String authorName;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
