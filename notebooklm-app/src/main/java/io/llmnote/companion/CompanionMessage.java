package io.llmnote.companion;

import io.llmnote.companion.enums.MessageRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 伴侣单条消息(用户/助手/系统三角色)。
 * attachments 与 metadata 是 JSON 字符串(Service 层用 ObjectMapper 解析/序列化):
 *   - attachments: [{url, kind:'image'|'audio'}, ...]
 *   - metadata:    {audioUrl, ttsFiles:[mp3 paths], model, usage:{...}, requestId, ...}
 */
@Data
@Entity
@Table(name = "companion_message")
public class CompanionMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "companion_id", nullable = false)
    private Long companionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private MessageRole role;

    @Column(nullable = false, columnDefinition = "MEDIUMTEXT")
    private String content;

    /** JSON 数组字符串;空为 "[]" 或 ""(Service 层负责)。 */
    @Column(nullable = false, length = 2048)
    private String attachments = "";

    @Column(nullable = false, length = 2048)
    private String metadata = "";

    @Column(nullable = false)
    private Boolean hidden = false;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
