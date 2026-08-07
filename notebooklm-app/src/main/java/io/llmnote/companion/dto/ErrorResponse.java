package io.llmnote.companion.dto;

/** 统一错误响应。 */
public record ErrorResponse(
        String error,
        String message
) {
}
