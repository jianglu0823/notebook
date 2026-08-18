package io.llmnote.world.forum;

import java.util.Map;

/**
 * 论坛 6 类目 → 图片/视频 prompt 风格修饰器(借鉴 Ultimate-AI-Media-Generator-Skill
 * curated-prompts.md 的"主体+镜头+光线+风格+画质"五段式拼装思路)。
 * <p>
 * 每个类目给出一个固定的英文视觉描述(镜头/构图/光线/画质),
 * 接到中文帖子内容后拼成完整 prompt,让 cogview-3-flash 出图时
 * 同一类目有稳定风格、不同类目有差异。
 */
public final class ImagePromptStyle {

    private ImagePromptStyle() {}

    /** 5 段拼接:主体 + 镜头 + 光线 + 风格 + 画质。 */
    record Modifier(String shot, String lighting, String style, String quality) {}

    /** 类目 → 修饰器。所有 key 与 ForumGenerationService.CATEGORIES 对齐。 */
    static final Map<String, Modifier> BY_CATEGORY = Map.of(
            // 段子:3D 卡通,亮色,夸张姿势
            "joke",         new Modifier(
                    "3D cartoon character pose, full-body framing, expressive face",
                    "bright pop colors, soft global illumination",
                    "Pixar-style 3D render, joyful mood, clean background",
                    "high detail, 8k"),
            // 藏头诗:水彩,微距,梦幻 bokeh
            "acrostic",     new Modifier(
                    "macro close-up of poetic objects (ink, brush, paper, flower)",
                    "soft natural light, dreamy bokeh",
                    "watercolor illustration, artistic, ethereal mood",
                    "ultra-detailed, soft palette"),
            // 日常分享:中景,自然光,生活摄影
            "daily",        new Modifier(
                    "medium shot, slice-of-life framing, natural composition",
                    "soft window light, golden hour tone",
                    "photorealistic lifestyle photography",
                    "high detail, film grain"),
            // 心灵鸡汤:广角日出,柔光,治愈
            "chicken_soup", new Modifier(
                    "wide landscape at sunrise, hopeful horizon",
                    "warm golden light, soft sunbeams, lens flare",
                    "cinematic inspirational photography",
                    "high detail, dramatic sky"),
            // 视频连载:电影感 16:9,电影颗粒
            "video_series", new Modifier(
                    "cinematic 16:9 wide shot, dramatic angle",
                    "moody cinematic lighting, rim light",
                    "film still, storyboard frame, movie color grade",
                    "high detail, film grain, anamorphic"),
            // 实用工具:产品图,棚拍
            "utility",      new Modifier(
                    "centered product on matte pedestal, clean composition",
                    "studio rim light, gradient background",
                    "minimalist product advertising",
                    "ultra-clean, high detail")
    );

    /** 视频类目专属修饰 — 默认走 cinematic motion(由 ForumMediaService 附加)。 */

    /**
     * 把中文帖子内容 + 类目 + 标题 + 作者 visualDescriptor 拼成完整英文 prompt。
     * <p>
     * 拼接顺序:[visualDescriptor if any] [title] | [content 摘要] | [shot] | [lighting] | [style] | [quality]
     * <p>
     * 标题(title)放最前,作为视觉锚;visualDescriptor 拼在主体前(角色一致性);
     * content 截 150 字符避免超 cogview token 上限。
     */
    public static String assemble(String category, String title, String content, String visualDescriptor) {
        Modifier m = BY_CATEGORY.getOrDefault(category, BY_CATEGORY.get("daily"));
        StringBuilder sb = new StringBuilder();
        // 2026-08-18 C 阶段:visualDescriptor 拼在主体前,确保 agent 角色稳定
        if (visualDescriptor != null && !visualDescriptor.isBlank()) {
            sb.append(visualDescriptor.trim()).append(" — ");
        }
        String subject;
        if (title != null && !title.isBlank()) {
            subject = title;
        } else if (content != null && !content.isBlank()) {
            subject = content.length() > 80 ? content.substring(0, 80) : content;
        } else {
            subject = category;
        }
        sb.append(subject);
        if (content != null && !content.isBlank() && (title == null || title.isBlank())) {
            String snippet = content.length() > 150 ? content.substring(0, 150) : content;
            sb.append(" — ").append(snippet);
        }
        sb.append(" | ").append(m.shot())
          .append(" | ").append(m.lighting())
          .append(" | ").append(m.style())
          .append(" | ").append(m.quality());
        return sb.toString();
    }

    /** 向后兼容:无 visualDescriptor。 */
    public static String assemble(String category, String title, String content) {
        return assemble(category, title, content, null);
    }

    /** 视频 prompt 装配:类目修饰 + cinematic motion 后缀。 */
    public static String assembleVideo(String category, String title, String content, String visualDescriptor) {
        return assemble(category, title, content, visualDescriptor) + ", cinematic motion, 24fps, smooth camera";
    }

    /** 向后兼容:无 visualDescriptor 的视频版本。 */
    public static String assembleVideo(String category, String title, String content) {
        return assembleVideo(category, title, content, null);
    }
}
