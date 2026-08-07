package io.llmnote.companion.enums;

/** 说话风格(语言层面)。 */
public enum SpeakingStyle {
    FORMAL("正式书面"),
    CASUAL("日常口语"),
    INTERNET("网络用语"),
    CLASSICAL("古风文言"),
    ACADEMIC("学术严谨"),
    CUTE("撒娇卖萌"),
    CONCISE("简洁干练");

    private final String label;

    SpeakingStyle(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
