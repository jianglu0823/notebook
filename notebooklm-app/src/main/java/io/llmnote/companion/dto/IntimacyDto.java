package io.llmnote.companion.dto;

import java.time.LocalDate;

/** 亲密度响应。 */
public record IntimacyDto(
        Long companionId,
        Integer score,
        Integer daysCount,
        LocalDate lastInteractDate,
        Boolean milestone7Sent,
        Boolean milestone30Sent,
        Boolean milestone100Sent
) {
}
