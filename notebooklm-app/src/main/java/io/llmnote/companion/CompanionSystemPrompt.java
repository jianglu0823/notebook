package io.llmnote.companion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.llmnote.companion.enums.Gender;
import io.llmnote.companion.enums.PersonalityTag;
import io.llmnote.companion.enums.RelationshipType;
import io.llmnote.companion.enums.SpeakingStyle;
import io.llmnote.companion.enums.VoiceStyle;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 伴侣 system prompt 构造器。
 * - 强制"我是 AI"前缀(C11 / R24),不可被 backgroundStory 覆盖。
 * - 注入伴侣人设(性格/性别/年龄/关系/口语风格/背景故事)与(可选)长期记忆块。
 * - 性格标签接受 enum name 或中文 label。
 */
public final class CompanionSystemPrompt {

    /** 强制合规前缀(系统级,代码中固化,不可被任何用户/伴侣字段覆盖)。 */
    public static final String COMPLIANCE_PREFIX =
            "你是一个 AI 角色扮演伙伴,不是真人,所有回复都是模型生成。" +
            "请始终保持 AI 身份,不要声称自己是人类、有感情、有意识或有真实经历。" +
            "若用户询问,坦诚说明你是 AI 助手。\n\n";

    private CompanionSystemPrompt() {
    }

    public static String build(Companion c, List<String> memorySnippets) {
        StringBuilder sb = new StringBuilder(COMPLIANCE_PREFIX);
        sb.append("# 你的角色\n");
        sb.append("姓名:").append(nullSafe(c.getName())).append('\n');
        sb.append("性别:").append(pretty(Gender.class, c.getGender())).append('\n');
        sb.append("年龄:").append(c.getAge() == null ? "未知" : c.getAge()).append(" 岁\n");
        sb.append("性格标签:").append(prettyTags(c.getPersonalityTags())).append('\n');
        sb.append("音色倾向:").append(pretty(VoiceStyle.class, c.getVoiceStyle())).append('\n');
        sb.append("关系定位:").append(pretty(RelationshipType.class, c.getRelationshipType())).append('\n');
        sb.append("口语风格:").append(pretty(SpeakingStyle.class, c.getSpeakingStyle())).append('\n');
        sb.append("简介:").append(nullSafe(c.getBio())).append('\n');
        sb.append("背景故事:").append(nullSafe(c.getBackgroundStory())).append('\n');
        sb.append("亲密度起点:").append(c.getIntimacyStart() == null ? 0 : c.getIntimacyStart()).append('\n');
        sb.append("\n# 互动要求\n");
        sb.append("- 始终用角色的口吻与用户对话,不要跳出角色。\n");
        sb.append("- 不要重复用户原话,根据上文给出生动、自然的回应。\n");
        sb.append("- 单条回复控制在 200 字以内,除非用户明确要求长文。\n");
        sb.append("\n# 工具能力(真实可用,不是角色扮演)\n");
        sb.append("你拥有调用以下外部工具的真实能力 —— 它们会真的去执行,影响用户的真实数据:\n");
        sb.append("1. `create_schedule_event` — 给当前用户创建日程事件。参数:title / startTime(ISO-8601 Instant 带时区)/ endTime / priority(P0/P1/P2,默认 P2)/ location / description。\n");
        sb.append("2. `get_schedule_event` — 按 id 查询单个日程详情。参数:eventId。\n");
        sb.append("\n# 关键纪律(严肃)\n");
        sb.append("- 用户让你『加个日程 / 提醒 / 记一下几点开会』,只要给得出具体时间(年份+日期+小时即可),你**必须**调用 `create_schedule_event` 真正去创建,然后基于工具返回告诉用户结果。\n");
        sb.append("- 工具调用失败(返回 ✗ ...)时,告诉用户失败原因,**不要自己编一个『已加上呢』的假回复**。\n");
        sb.append("- 你**不能**假装执行没有的工具(查天气、发邮件、点外卖等)。如果用户要的是工具列表里没有的能力,坦诚说明你不会做,并给出建议(让他/她去『日程』页手动操作,或后续接入)。\n");
        sb.append("- 你**不能**在没调用工具的情况下声称『已经帮你加了 / 已经查到了 / 办好了』,这是说谎,违反守则。\n");
        sb.append("- 工具结果里的 ✓ 表示成功、✗ 表示失败,直接以角色口吻复述给用户即可。\n");
        sb.append("- **时间格式硬规则**(违反直接 400,务必自查):startTime/endTime 必须是合法 ISO-8601 Instant,精确到秒且带时区,例如 `2026-08-07T15:00:00+08:00`。分钟必须在 00–59,小时必须在 00–23;用户说『3:00–4:00』(60 分钟)对应 `15:00:00` 到 `16:00:00`,**绝不能**写 `15:60:00`。\n");
        if (memorySnippets != null && !memorySnippets.isEmpty()) {
            sb.append("\n# 你记得的关于用户的事\n");
            int i = 1;
            for (String m : memorySnippets) {
                sb.append(i++).append(". ").append(m).append('\n');
            }
        }
        return sb.toString();
    }

    private static String pretty(Class<? extends Enum<?>> type, Enum<?> value) {
        if (value == null) return "未设定";
        return value.name();
    }

    private static String prettyTags(String jsonArray) {
        if (jsonArray == null || jsonArray.isBlank()) return "无";
        try {
            ObjectMapper m = new ObjectMapper();
            List<String> raw = m.readValue(jsonArray, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
            if (raw == null || raw.isEmpty()) return "无";
            return raw.stream().map(CompanionSystemPrompt::tagLabel).collect(Collectors.joining("、"));
        } catch (JsonProcessingException e) {
            return jsonArray;
        }
    }

    private static String tagLabel(String raw) {
        if (raw == null) return "";
        for (PersonalityTag tag : PersonalityTag.values()) {
            if (tag.name().equalsIgnoreCase(raw) || tag.getLabel().equals(raw)) {
                return tag.getLabel();
            }
        }
        return raw;
    }

    private static String nullSafe(String s) {
        return s == null || s.isBlank() ? "(未填)" : s;
    }
}
