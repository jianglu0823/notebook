package io.llmnote.companion.ws;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ClientFrame(
        String kind,
        Long companionId,
        String content,
        String model,
        Long messageId,
        JsonNode attachments,
        Boolean voiceReply
) {
}
