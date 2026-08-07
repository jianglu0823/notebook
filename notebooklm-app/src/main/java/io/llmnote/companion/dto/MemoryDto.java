package io.llmnote.companion.dto;

import java.time.LocalDateTime;

/** 长期记忆响应。 */
public record MemoryDto(
        Long id,
        Long companionId,
        String content,
        Long sourceMessageId,
        LocalDateTime createdAt
) {
}
