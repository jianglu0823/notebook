package io.llmnote.companion;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 长期记忆元数据(向量本体存 Milvus companion_memories,通过 memory_id 关联)。
 * 删除为软删(deleted=true),同时从 Milvus 删对应向量。
 */
@Data
@Entity
@Table(name = "companion_memory")
public class CompanionMemory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "companion_id", nullable = false)
    private Long companionId;

    /** 冗余 userId,便于按用户过滤检索与未来跨伴侣聚合。 */
    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(nullable = false, length = 255)
    private String content;

    @Column(name = "source_message_id")
    private Long sourceMessageId;

    @Column(nullable = false)
    private Boolean deleted = false;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
