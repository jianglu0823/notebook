package io.llmnote.companion.enums;

/** 与用户的关系类型。 */
public enum RelationshipType {
    LOVER("恋人"),
    BESTIE("挚友"),
    INTIMATE("知己"),
    MENTOR("导师"),
    BUTLER("管家"),
    CUSTOM("自定义");

    private final String label;

    RelationshipType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
