package io.llmnote.companion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.llmnote.companion.dto.AttachmentDto;
import io.llmnote.companion.dto.CompanionCreateRequest;
import io.llmnote.companion.dto.CompanionDto;
import io.llmnote.companion.dto.CompanionPatchRequest;
import io.llmnote.companion.dto.CompanionTemplateDto;
import io.llmnote.companion.dto.MessageDto;
import io.llmnote.companion.enums.Gender;
import io.llmnote.companion.enums.PersonalityTag;
import io.llmnote.companion.enums.RelationshipType;
import io.llmnote.companion.enums.SpeakingStyle;
import io.llmnote.companion.enums.VoiceStyle;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Companion 实体 ↔ DTO 互转(手写版,项目无 MapStruct)。
 * - personalityTags / attachments / metadata 在 entity 中是 JSON 字符串,这里做解析/序列化。
 * - 枚举字段以字符串名落库,DTO 也输出字符串,便于前端 i18n。
 */
@Component
public class CompanionMapper {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private static final TypeReference<List<AttachmentDto>> ATTACHMENT_LIST = new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> META_MAP = new TypeReference<>() {};

    private final ObjectMapper objectMapper;

    public CompanionMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // ===== Companion → DTO =====

    public CompanionDto toDto(Companion c) {
        if (c == null) return null;
        return new CompanionDto(
                c.getId(),
                c.getName(),
                enumName(c.getGender()),
                c.getAge(),
                parseStringList(c.getPersonalityTags()),
                c.getBio(),
                enumName(c.getVoiceStyle()),
                enumName(c.getRelationshipType()),
                c.getIntimacyStart(),
                enumName(c.getSpeakingStyle()),
                c.getBackgroundStory(),
                c.getDefaultModel(),
                c.getStatus(),
                c.getIntimacyScore(),
                c.getLastMessageAt(),
                c.getCreatedAt(),
                c.getUpdatedAt()
        );
    }

    public CompanionTemplateDto toTemplateDto(CompanionTemplate t) {
        if (t == null) return null;
        return new CompanionTemplateDto(
                t.getTemplateKey(),
                t.getName(),
                enumName(t.getGender()),
                t.getAge(),
                parseStringList(t.getPersonalityTags()),
                t.getBio(),
                enumName(t.getVoiceStyle()),
                enumName(t.getRelationshipType()),
                t.getIntimacyStart(),
                enumName(t.getSpeakingStyle()),
                t.getBackgroundStory(),
                t.getDefaultModel()
        );
    }

    public MessageDto toMessageDto(CompanionMessage m) {
        if (m == null) return null;
        return new MessageDto(
                m.getId(),
                m.getCompanionId(),
                m.getRole() == null ? null : m.getRole().name(),
                m.getContent(),
                parseAttachmentList(m.getAttachments()),
                parseMetaMap(m.getMetadata()),
                m.getCreatedAt()
        );
    }

    // ===== DTO → Entity =====

    public Companion fromCreate(CompanionCreateRequest req, String userId, String defaultModel) {
        Companion c = new Companion();
        c.setUserId(userId);
        applyCommon(c, req.name(), req.gender(), req.age(), req.personalityTags(),
                req.bio(), req.voiceStyle(), req.relationshipType(), req.intimacyStart(),
                req.speakingStyle(), req.backgroundStory(),
                req.defaultModel() != null && !req.defaultModel().isBlank() ? req.defaultModel() : defaultModel);
        return c;
    }

    public void applyPatch(Companion c, CompanionPatchRequest req, String defaultModel) {
        if (req == null) return;
        if (req.name() != null) c.setName(req.name());
        if (req.gender() != null) c.setGender(parseEnum(Gender.class, req.gender(), c.getGender()));
        if (req.age() != null) c.setAge(req.age());
        if (req.personalityTags() != null) c.setPersonalityTags(toJsonArray(req.personalityTags()));
        if (req.bio() != null) c.setBio(req.bio());
        if (req.voiceStyle() != null) c.setVoiceStyle(parseEnum(VoiceStyle.class, req.voiceStyle(), c.getVoiceStyle()));
        if (req.relationshipType() != null) c.setRelationshipType(parseEnum(RelationshipType.class, req.relationshipType(), c.getRelationshipType()));
        if (req.intimacyStart() != null) c.setIntimacyStart(req.intimacyStart());
        if (req.speakingStyle() != null) c.setSpeakingStyle(parseEnum(SpeakingStyle.class, req.speakingStyle(), c.getSpeakingStyle()));
        if (req.backgroundStory() != null) c.setBackgroundStory(req.backgroundStory());
        if (req.defaultModel() != null) c.setDefaultModel(req.defaultModel());
    }

    public Companion fromTemplate(CompanionTemplate t, String userId) {
        Companion c = new Companion();
        c.setUserId(userId);
        c.setName(t.getName());
        c.setGender(t.getGender());
        c.setAge(t.getAge());
        c.setPersonalityTags(t.getPersonalityTags());
        c.setBio(t.getBio());
        c.setVoiceStyle(t.getVoiceStyle());
        c.setRelationshipType(t.getRelationshipType());
        c.setIntimacyStart(t.getIntimacyStart());
        c.setSpeakingStyle(t.getSpeakingStyle());
        c.setBackgroundStory(t.getBackgroundStory());
        c.setDefaultModel(t.getDefaultModel());
        return c;
    }

    // ===== PersonalityTag 兼容 =====

    /**
     * 接受 enum name(GENTLE)、中文 label(温柔体贴)或空,统一输出 enum name。
     * 找不到匹配的保留原文,落库时是字符串。
     */
    public String normalizeTag(String raw) {
        if (raw == null || raw.isBlank()) return raw;
        String trimmed = raw.trim();
        for (PersonalityTag tag : PersonalityTag.values()) {
            if (tag.name().equalsIgnoreCase(trimmed) || tag.getLabel().equals(trimmed)) {
                return tag.name();
            }
        }
        return trimmed;
    }

    public String normalizeTagList(List<String> raws) {
        if (raws == null || raws.isEmpty()) return "[]";
        List<String> normalized = raws.stream().map(this::normalizeTag).toList();
        return toJsonArray(normalized);
    }

    // ===== helpers =====

    private void applyCommon(Companion c, String name, String gender, Integer age, List<String> tags,
                             String bio, String voiceStyle, String relationshipType, Integer intimacyStart,
                             String speakingStyle, String backgroundStory, String defaultModel) {
        c.setName(name);
        c.setGender(parseEnum(Gender.class, gender, Gender.UNSPECIFIED));
        c.setAge(age != null ? age : 25);
        c.setPersonalityTags(normalizeTagList(tags));
        c.setBio(bio != null ? bio : "");
        c.setVoiceStyle(parseEnum(VoiceStyle.class, voiceStyle, VoiceStyle.SWEET));
        c.setRelationshipType(parseEnum(RelationshipType.class, relationshipType, RelationshipType.INTIMATE));
        c.setIntimacyStart(intimacyStart != null ? intimacyStart : 30);
        c.setSpeakingStyle(parseEnum(SpeakingStyle.class, speakingStyle, SpeakingStyle.CASUAL));
        c.setBackgroundStory(backgroundStory != null ? backgroundStory : "");
        c.setDefaultModel(defaultModel);
    }

    private String enumName(Enum<?> e) {
        return e == null ? null : e.name();
    }

    private <E extends Enum<E>> E parseEnum(Class<E> type, String value, E fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return Enum.valueOf(type, value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private String toJsonArray(List<String> list) {
        try {
            return objectMapper.writeValueAsString(list);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    private List<String> parseStringList(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            List<String> list = objectMapper.readValue(json, STRING_LIST);
            return list == null ? Collections.emptyList() : list;
        } catch (JsonProcessingException e) {
            return Collections.emptyList();
        }
    }

    private List<AttachmentDto> parseAttachmentList(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            return objectMapper.readValue(json, ATTACHMENT_LIST);
        } catch (JsonProcessingException e) {
            return Collections.emptyList();
        }
    }

    private Map<String, Object> parseMetaMap(String json) {
        if (json == null || json.isBlank()) return Collections.emptyMap();
        try {
            Map<String, Object> map = objectMapper.readValue(json, META_MAP);
            return map == null ? Collections.emptyMap() : map;
        } catch (JsonProcessingException e) {
            return Collections.emptyMap();
        }
    }
}
