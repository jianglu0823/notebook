package io.llmnote.companion.dto;

/** 上传图片响应(返回 url 供 chat.text 帧的 attachments 使用)。 */
public record UploadResponse(
        String url,
        Long sizeBytes
) {
}
