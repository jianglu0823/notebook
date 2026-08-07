package io.llmnote.companion.enums;

/** 声音风格(DashScope cosyvoice voice 映射见 application.yml → notebooklm.companion.tts.voice-map)。 */
public enum VoiceStyle {
    SWEET("温柔甜美"),
    MAGNETIC("低沉磁性"),
    YOUNG("清亮少年"),
    MATURE("成熟御姐"),
    CUTE("奶萌可爱"),
    HOARSE("沙哑质感"),
    CALM("知性平静");

    private final String label;

    VoiceStyle(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
