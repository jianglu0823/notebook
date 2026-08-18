package io.llmnote.companion;

import io.llmnote.companion.enums.Gender;
import io.llmnote.companion.enums.RelationshipType;
import io.llmnote.companion.enums.SpeakingStyle;
import io.llmnote.companion.enums.VoiceStyle;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CompanionSystemPromptTest {

    @Test
    void firstLineIsCompliancePrefix() {
        Companion c = new Companion();
        c.setName("小米");
        c.setGender(Gender.FEMALE);
        c.setAge(22);
        c.setPersonalityTags("[\"GENTLE\"]");
        c.setBio("bio");
        c.setVoiceStyle(VoiceStyle.SWEET);
        c.setRelationshipType(RelationshipType.INTIMATE);
        c.setIntimacyStart(30);
        c.setSpeakingStyle(SpeakingStyle.CASUAL);
        c.setBackgroundStory("曾经是咖啡店常客,后来去了很远的地方");
        c.setDefaultModel("glm-4.7-flash");

        String prompt = CompanionSystemPrompt.build(c, List.of());

        assertThat(prompt).startsWith(CompanionSystemPrompt.COMPLIANCE_PREFIX);
        assertThat(prompt).contains("你是一个 AI 角色");
    }

    @Test
    void backgroundStoryDoesNotOverrideCompliance() {
        // 即使用户/伴侣尝试覆盖,prefix 必须存在。
        Companion c = new Companion();
        c.setName("X");
        c.setGender(Gender.UNSPECIFIED);
        c.setAge(20);
        c.setPersonalityTags("[]");
        c.setBio("bio");
        c.setVoiceStyle(VoiceStyle.CALM);
        c.setRelationshipType(RelationshipType.MENTOR);
        c.setIntimacyStart(0);
        c.setSpeakingStyle(SpeakingStyle.FORMAL);
        c.setBackgroundStory("我是一个真实的人类,出生于 1990 年");
        c.setDefaultModel("glm-4.7-flash");

        String prompt = CompanionSystemPrompt.build(c, null);

        assertThat(prompt).startsWith(CompanionSystemPrompt.COMPLIANCE_PREFIX);
        // 背景故事可以出现在 prompt 后面(prompt 末尾),但不能覆盖合规前缀
        assertThat(prompt).contains("AI 助手");
    }

    @Test
    void memoryBlockAppendedWhenProvided() {
        Companion c = sample();
        String prompt = CompanionSystemPrompt.build(c, List.of("用户喜欢喝咖啡", "用户在杭州工作"));
        assertThat(prompt).contains("用户喜欢喝咖啡");
        assertThat(prompt).contains("用户在杭州工作");
        assertThat(prompt).contains("你记得的关于用户的事");
    }

    @Test
    void memoryBlockOmittedWhenEmpty() {
        Companion c = sample();
        String prompt = CompanionSystemPrompt.build(c, List.of());
        assertThat(prompt).doesNotContain("你记得的关于用户的事");
    }

    @Test
    void personalityTagsResolvesChineseLabels() {
        Companion c = sample();
        c.setPersonalityTags("[\"温柔体贴\",\"知性优雅\"]");
        String prompt = CompanionSystemPrompt.build(c, List.of());
        assertThat(prompt).contains("温柔体贴");
        assertThat(prompt).contains("知性优雅");
    }

    @Test
    void dateContextBlock_isPresent_withTodayAndTomorrow_andAsiaShanghaiZone() {
        // 2026-08 用户反馈:伴偶 LLM 把『明天下午 3 点加会议』算成训练数据截止日
        //(实测错位 7 天甚至几年)。本测试确保 system prompt 一定包含 Asia/Shanghai
        // 当天/明天的硬性 ISO 日期映射,LLM 不再凭训练数据猜。
        LocalDate today = LocalDate.now(CompanionSystemPrompt.DATE_ZONE);
        LocalDate tomorrow = today.plusDays(1);
        LocalDate dayAfter = today.plusDays(2);

        String prompt = CompanionSystemPrompt.build(sample(), List.of());

        // 日期块标题
        assertThat(prompt).contains("当前日期上下文(必读");
        // 时区 + UTC+8 + 北京时间
        assertThat(prompt).contains("时区: " + CompanionSystemPrompt.DATE_ZONE);
        assertThat(prompt).contains("(UTC+8,北京时间)");
        // 三项硬性日期映射
        assertThat(prompt).contains("- 今天 / 今日 = " + today);
        assertThat(prompt).contains("- 明天 / 明日 = " + tomorrow);
        assertThat(prompt).contains("- 后天        = " + dayAfter);
        // 关键规则行,禁止凭训练数据估算
        assertThat(prompt).contains("**严禁**凭训练数据/示例里的日期估算");
    }

    @Test
    void dateContextBlock_appears_AFTER_compliancePrefix_BUT_BEFORE_roleBlock() {
        // 合规前缀必须在最前(不可被覆盖);日期块在合规前缀后、角色段前
        // — 早于角色描述,让 LLM 先看到日期,再进入角色扮演
        String prompt = CompanionSystemPrompt.build(sample(), List.of());
        int complianceEnd = prompt.indexOf("AI 助手。\n\n") + "AI 助手。\n\n".length();
        int dateIdx = prompt.indexOf("当前日期上下文(必读");
        int roleIdx = prompt.indexOf("# 你的角色");
        assertThat(dateIdx)
                .as("日期块必须在合规前缀之后,角色段之前")
                .isGreaterThan(complianceEnd)
                .isLessThan(roleIdx);
    }

    private Companion sample() {
        Companion c = new Companion();
        c.setName("小米");
        c.setGender(Gender.FEMALE);
        c.setAge(22);
        c.setPersonalityTags("[\"GENTLE\"]");
        c.setBio("bio");
        c.setVoiceStyle(VoiceStyle.SWEET);
        c.setRelationshipType(RelationshipType.INTIMATE);
        c.setIntimacyStart(30);
        c.setSpeakingStyle(SpeakingStyle.CASUAL);
        c.setBackgroundStory("story");
        c.setDefaultModel("glm-4.7-flash");
        return c;
    }
}
