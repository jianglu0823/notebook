package io.llmnote.companion.enums;

/** 性格标签(枚举全部 15 个,最多选 5 个)。 */
public enum PersonalityTag {
    GENTLE("温柔体贴"),
    HUMOROUS("幽默风趣"),
    ALOOF("高冷傲娇"),
    ELEGANT("知性优雅"),
    LIVELY("活泼开朗"),
    CALM("沉稳内敛"),
    SHARP("毒舌犀利"),
    WARM("暖男暖女"),
    YANDERE("病娇"),
    SCHEMING("腹黑"),
    AIRHEAD("天然呆"),
    RATIONAL("理性冷静"),
    ROMANTIC("感性浪漫"),
    LITERARY("文艺青年"),
    ENERGETIC("元气满满");

    private final String label;

    PersonalityTag(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
