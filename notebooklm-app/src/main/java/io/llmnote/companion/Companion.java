package io.llmnote.companion;

import io.llmnote.companion.enums.Gender;
import io.llmnote.companion.enums.RelationshipType;
import io.llmnote.companion.enums.SpeakingStyle;
import io.llmnote.companion.enums.VoiceStyle;
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
 * AI 伴侣元数据。
 * user_id 既支持登录用户(数字字符串)也支持游客(guest uuid)。
 * personality_tags 用 JSON 字符串持久化(最多 5 个 PersonalityTag 枚举名),由 Service 层用 ObjectMapper 转换。
 * defaultModel 落库时已过 ChatModelFactory.normalize()。
 * 删除为软删(status='DELETED'),同时联级软删该伴侣的 message/memory。
 */
@Data
@Entity
@Table(name = "companion")
public class Companion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(nullable = false, length = 20)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Gender gender = Gender.UNSPECIFIED;

    @Column(nullable = false)
    private Integer age = 25;

    /** JSON 数组字符串,如 ["GENTLE","ELEGANT"];Service 层用 ObjectMapper 解析。 */
    @Column(name = "personality_tags", nullable = false, length = 255)
    private String personalityTags = "";

    @Column(nullable = false, length = 200)
    private String bio = "";

    @Enumerated(EnumType.STRING)
    @Column(name = "voice_style", nullable = false, length = 32)
    private VoiceStyle voiceStyle = VoiceStyle.SWEET;

    @Enumerated(EnumType.STRING)
    @Column(name = "relationship_type", nullable = false, length = 16)
    private RelationshipType relationshipType = RelationshipType.INTIMATE;

    @Column(name = "intimacy_start", nullable = false)
    private Integer intimacyStart = 30;

    @Enumerated(EnumType.STRING)
    @Column(name = "speaking_style", nullable = false, length = 32)
    private SpeakingStyle speakingStyle = SpeakingStyle.CASUAL;

    @Column(name = "background_story", nullable = false, length = 500)
    private String backgroundStory = "";

    @Column(name = "default_model", nullable = false, length = 64)
    private String defaultModel = "glm-4.7-flash";

    @Column(nullable = false, length = 16)
    private String status = "ACTIVE";

    /** 冗余当前亲密度(便于列表展示;权威值在 companion_intimacy.score)。 */
    @Column(name = "intimacy_score", nullable = false)
    private Integer intimacyScore = 0;

    @Column(name = "last_message_at")
    private LocalDateTime lastMessageAt;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}
