package io.llmnote.world.forum;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A 阶段:ImagePromptStyle 单元测试。验证 6 类目修饰器都正确组装,
 * 标题/内容/缺省情况都覆盖。
 */
class ImagePromptStyleTest {

    @Test
    void assemble_joke_usesCartoon3D() {
        String p = ImagePromptStyle.assemble("joke", "我的猫会跳舞",
                "我家小猫今天在客厅跳了一支舞,全场笑翻");
        assertThat(p).startsWith("我的猫会跳舞");
        assertThat(p).contains("3D cartoon");
        assertThat(p).contains("Pixar-style");
        assertThat(p).contains("8k");
    }

    @Test
    void assemble_daily_usesPhotorealisticLifestyle() {
        String p = ImagePromptStyle.assemble("daily", null, "今天去了西湖,柳树发芽了");
        assertThat(p).contains("photorealistic lifestyle");
        assertThat(p).contains("golden hour");
    }

    @Test
    void assemble_chickenSoup_usesCinematicSunrise() {
        String p = ImagePromptStyle.assemble("chicken_soup", null, "只要坚持,就有希望");
        assertThat(p).contains("sunrise");
        assertThat(p).contains("cinematic inspirational");
    }

    @Test
    void assemble_videoSeries_usesAnamorphicWidescreen() {
        String p = ImagePromptStyle.assemble("video_series", "小镇奇缘 EP01", "主角在咖啡馆遇见神秘来客");
        assertThat(p).contains("16:9");
        assertThat(p).contains("anamorphic");
    }

    @Test
    void assemble_utility_usesMinimalistProduct() {
        String p = ImagePromptStyle.assemble("utility", "情侣头像合集", "10 款 CP 头像");
        assertThat(p).contains("matte pedestal");
        assertThat(p).contains("minimalist product");
    }

    @Test
    void assemble_unknownCategory_fallsBackToDailyStyle() {
        String p = ImagePromptStyle.assemble("mystery_category", null, "hello");
        assertThat(p).contains("photorealistic lifestyle");
    }

    @Test
    void assemble_nullTitleAndContent_usesCategoryAsSubject() {
        String p = ImagePromptStyle.assemble("joke", null, null);
        assertThat(p).startsWith("joke |");
    }

    @Test
    void assembleVideo_appendsCinematicMotion() {
        String p = ImagePromptStyle.assembleVideo("daily", null, "西湖一日游");
        assertThat(p).contains("cinematic motion");
        assertThat(p).contains("24fps");
        assertThat(p).contains("smooth camera");
    }

    @Test
    void assemble_truncatesLongContentToAvoidTokenOverflow() {
        String longContent = "a".repeat(500);
        String p = ImagePromptStyle.assemble("daily", null, longContent);
        assertThat(p.length()).isLessThan(400);
    }

    // 2026-08-18 C 阶段:visualDescriptor 拼到主体前,保证角色一致性。
    @Test
    void assemble_withVisualDescriptor_prependsBeforeSubject() {
        String visual = "a young woman with short black bob, round glasses, blue denim jacket";
        String p = ImagePromptStyle.assemble("joke", "我的猫会跳舞", "在客厅跳了一支舞", visual);
        // visual 必须在 title 前面
        int visualIdx = p.indexOf("short black bob");
        int titleIdx = p.indexOf("我的猫会跳舞");
        assertThat(visualIdx).isLessThan(titleIdx);
        assertThat(p).contains("blue denim jacket");
    }

    @Test
    void assemble_blankVisualDescriptor_fallsBackToPlainSubject() {
        String p = ImagePromptStyle.assemble("daily", null, "西湖一日游", "  ");
        // 空白 visualDescriptor 视为 null,不拼
        assertThat(p).startsWith("西湖一日游");
        assertThat(p).doesNotContain("— —");
    }

    @Test
    void assembleVideo_withVisualDescriptor_includesBoth() {
        String visual = "an elderly Chinese man, white beard, blue tangzhuang";
        String p = ImagePromptStyle.assembleVideo("chicken_soup", null, "坚持就是胜利", visual);
        assertThat(p).contains("elderly Chinese man");
        assertThat(p).contains("坚持就是胜利");
        assertThat(p).contains("cinematic motion");
    }
}
