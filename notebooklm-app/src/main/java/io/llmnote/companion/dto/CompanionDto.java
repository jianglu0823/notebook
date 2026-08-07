package io.llmnote.companion.dto;

import java.time.LocalDateTime;
import java.util.List;

/** 伴侣响应 DTO(personalityTags 由 Service 反序列化 JSON 字符串得到)。 */
public record CompanionDto(
        Long id,
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
        String defaultModel,
        String status,
        Integer intimacyScore,
        LocalDateTime lastMessageAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
