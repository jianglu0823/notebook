package io.llmnote.companion.dto;

import java.util.List;

/** 伴侣部分更新请求(所有字段可选,null 表示不改)。 */
public record CompanionPatchRequest(
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
