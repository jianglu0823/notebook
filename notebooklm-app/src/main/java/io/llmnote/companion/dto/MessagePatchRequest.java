package io.llmnote.companion.dto;

/** 消息操作请求;action: "delete" | "regenerate"。 */
public record MessagePatchRequest(
        String action
) {
}
