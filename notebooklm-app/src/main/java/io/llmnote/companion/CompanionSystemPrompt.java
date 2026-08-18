package io.llmnote.companion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.llmnote.companion.enums.Gender;
import io.llmnote.companion.enums.PersonalityTag;
import io.llmnote.companion.enums.RelationshipType;
import io.llmnote.companion.enums.SpeakingStyle;
import io.llmnote.companion.enums.VoiceStyle;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 伴侣 system prompt 构造器。
 * - 强制"我是 AI"前缀(C11 / R24),不可被 backgroundStory 覆盖。
 * - 注入伴侣人设(性格/性别/年龄/关系/口语风格/背景故事)与(可选)长期记忆块。
 * - 性格标签接受 enum name 或中文 label。
 * - 注入"当前日期上下文"块(2026-08 用户反馈:伴偶 LLM 在用户说『明天下午 3 点加
 *   会议』时,因训练数据截止导致把日期算错),显式告诉 LLM 今天/明天/后天是哪个
 *   ISO 日期,避免猜错。schedule-agent 端也有同样的注入;伴偶侧再补一层,确保
 *   delegate_to_schedule_agent 的 userText 不被伴偶 LLM 错误修改。
 */
public final class CompanionSystemPrompt {

    /** 强制合规前缀(系统级,代码中固化,不可被任何用户/伴侣字段覆盖)。 */
    public static final String COMPLIANCE_PREFIX =
            "你是一个 AI 角色扮演伙伴,不是真人,所有回复都是模型生成。" +
            "请始终保持 AI 身份,不要声称自己是人类、有感情、有意识或有真实经历。" +
            "若用户询问,坦诚说明你是 AI 助手。\n\n";

    /** 伴偶侧的日期时区 — 与 schedule-agent 的 CurrentDateMiddleware 保持一致。 */
    public static final ZoneId DATE_ZONE = ZoneId.of("Asia/Shanghai");

    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter CHINESE_DATE =
            DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE", Locale.SIMPLIFIED_CHINESE);

    private CompanionSystemPrompt() {
    }

    public static String build(Companion c, List<String> memorySnippets) {
        StringBuilder sb = new StringBuilder(COMPLIANCE_PREFIX);
        sb.append("# 当前日期上下文(必读,解析『今天/明天/后天/本周X』前先看这里)\n");
        appendDateContext(sb);
        sb.append("\n# 你的角色\n");
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
        sb.append("你拥有**唯一**一个真实可用的外部工具 —— 它会真的去执行,影响用户的真实数据:\n");
        sb.append("- `delegate_to_schedule_agent(userText, context?)` — 把你这一轮要做的『日程相关操作』(创建/查询/修改/删除/列日程/专注打卡等)用自然语言原样写到 `userText` 参数里(包含标题、时间、地点、优先级等所有用户提到的信息,不要替用户决定或省略),工具会真的去日程微服务里执行,返回成功/失败结果。\n");
        sb.append("- 重要:这个工具是『透传』桥,它内部会自己决定调哪个子工具(创建事件 / 查询事件 / 列日程 / 打卡等),**你不需要、也不应该在 userText 里自己拼子工具调用**。你只负责把用户的原话和必要上下文写进 userText。\n");
        sb.append("\n# 关键纪律(严肃,违反会被工程层拦截)\n");
        sb.append("- 用户让你『加个日程 / 提醒 / 记一下几点开会 / 查下明天有什么 / 把这个会议改到下午 3 点 / 删掉那个日程』—— 只要属于日程域,**必须先**调用 `delegate_to_schedule_agent` 拿到真实结果,才能回复用户。\n");
        sb.append("- 工具返回成功:用角色口吻告诉用户结果(包含事件标题、时间、id 等关键信息)。\n");
        sb.append("- 工具返回失败(返回 ✗ ...):告诉用户失败原因,**不要编一个『已加上呢』的假回复**。\n");
        sb.append("- **绝对禁止**在没调用工具的情况下声称『已经帮你加了 / 已经查到了 / 办好了 / 提交打卡了』。即使你『觉得』应该成功,也必须等工具返回。\n");
        sb.append("- **绝对禁止**自行编造事件标题、时间、id 等字段值 —— 所有具体数据必须来自工具返回。\n");
        sb.append("- 你**不能**假装执行没有的工具(查天气、发邮件、点外卖、控制智能家居等);坦诚说明你不会做,并建议用户在『日程』页手动操作。\n");
        sb.append("- 工具结果里的 ✓ 表示成功、✗ 表示失败,直接以角色口吻复述即可。\n");
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

    /**
     * 把 Asia/Shanghai 当天/明天/后天/本周剩余/下周注入到 prompt。
     * 防止伴偶 LLM 把『明天/后天』算成训练数据截止日(2026-08 用户反馈 glm-4.6
     * 把"明天下午 3 点加会议"算错日期)。日期块简短,避免挤掉角色/工具段。
     */
    private static void appendDateContext(StringBuilder sb) {
        LocalDate today = LocalDate.now(DATE_ZONE);
        LocalDate tomorrow = today.plusDays(1);
        LocalDate dayAfter = today.plusDays(2);
        LocalDate endOfWeek = today.with(DayOfWeek.SUNDAY);
        LocalDate startOfNextWeek = endOfWeek.plusDays(1);
        String dow = today.getDayOfWeek().getDisplayName(
                java.time.format.TextStyle.SHORT, Locale.SIMPLIFIED_CHINESE);
        sb.append("- 时区: ").append(DATE_ZONE).append(" (UTC+8,北京时间),以下日期均按此时区计算:\n");
        sb.append("- 今天 / 今日 = ").append(today.format(ISO_DATE))
                .append(" (").append(today.format(CHINESE_DATE)).append(")\n");
        sb.append("- 明天 / 明日 = ").append(tomorrow.format(ISO_DATE))
                .append(" (").append(tomorrow.format(CHINESE_DATE)).append(")\n");
        sb.append("- 后天        = ").append(dayAfter.format(ISO_DATE))
                .append(" (").append(dayAfter.format(CHINESE_DATE)).append(")\n");
        sb.append("- 本周剩余截至 ").append(endOfWeek.format(ISO_DATE))
                .append(",下周从 ").append(startOfNextWeek.format(ISO_DATE)).append(" 起\n");
        sb.append("- 现在星期: ").append(dow).append('\n');
        sb.append("- 关键规则:用户说『明天/明日/后天/下周一/本周X/明早/明晚』时,"
                + "必须按上表日期换算,**严禁**凭训练数据/示例里的日期估算。\n");
    }
}
