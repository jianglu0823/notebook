package io.llmnote.companion.dto;

import java.util.List;

/** 伴侣预设模板响应。 */
public record CompanionTemplateDto(
        String templateKey,
        String name,
        String gender,
        Integer age,
        List<String> personalityTags,
        String bio,
        String voiceStyle,
        String relationshipType,
        Integer intimacyStart,
        String speakingStyle,
        String backgroundStory,
        String defaultModel
) {
}
