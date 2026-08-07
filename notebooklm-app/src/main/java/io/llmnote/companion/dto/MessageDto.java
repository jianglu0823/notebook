package io.llmnote.companion.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 消息响应(attachments 与 metadata 是已反序列化的对象)。 */
public record MessageDto(
        Long id,
        Long companionId,
        String role,
        String content,
        List<AttachmentDto> attachments,
        Map<String, Object> metadata,
        LocalDateTime createdAt
) {
}
