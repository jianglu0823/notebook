package io.llmnote.companion.dto;

/** 模型下拉项(name/label/单价元/千 token)。 */
public record ModelDto(
        String name,
        String label,
        double inputPer1k,
        double outputPer1k
) {
}
