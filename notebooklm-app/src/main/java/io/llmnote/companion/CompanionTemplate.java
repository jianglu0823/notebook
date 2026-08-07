package io.llmnote.companion;

import io.llmnote.companion.enums.Gender;
import io.llmnote.companion.enums.RelationshipType;
import io.llmnote.companion.enums.SpeakingStyle;
import io.llmnote.companion.enums.VoiceStyle;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 伴侣预设模板(12 个,启动时由 seeder load)。
 * template_key 为主键,如 gentle_senior / domestic_ceo / energetic_girl。
 */
@Data
@Entity
@Table(name = "companion_template")
public class CompanionTemplate {

    @Id
    @Column(name = "template_key", length = 32)
    private String templateKey;

    @Column(nullable = false, length = 20)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Gender gender;

    @Column(nullable = false)
    private Integer age;

    @Column(name = "personality_tags", nullable = false, length = 255)
    private String personalityTags;

    @Column(nullable = false, length = 200)
    private String bio = "";

    @Enumerated(EnumType.STRING)
    @Column(name = "voice_style", nullable = false, length = 32)
    private VoiceStyle voiceStyle;

    @Enumerated(EnumType.STRING)
    @Column(name = "relationship_type", nullable = false, length = 16)
    private RelationshipType relationshipType;

    @Column(name = "intimacy_start", nullable = false)
    private Integer intimacyStart;

    @Enumerated(EnumType.STRING)
    @Column(name = "speaking_style", nullable = false, length = 32)
    private SpeakingStyle speakingStyle;

    @Column(name = "background_story", nullable = false, length = 500)
    private String backgroundStory;

    @Column(name = "default_model", nullable = false, length = 64)
    private String defaultModel;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
