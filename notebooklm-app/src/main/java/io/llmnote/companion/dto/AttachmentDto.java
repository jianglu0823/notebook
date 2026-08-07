package io.llmnote.companion.dto;

/** 聊天附件(图片/音频)。 */
public record AttachmentDto(
        String url,
        String kind  // "image" | "audio"
) {
}
