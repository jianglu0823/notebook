package io.llmnote.companion.dto;

import java.util.List;

/** 伴侣创建请求(5 字段快速 + 可选扩展)。 */
public record CompanionCreateRequest(
        String name,
        String gender,
        List<String> personalityTags,
        Integer age,
        String bio,
        String voiceStyle,
        String relationshipType,
        Integer intimacyStart,
        String speakingStyle,
        String backgroundStory,
        String defaultModel
) {
}
