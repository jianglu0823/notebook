package io.llmnote.companion;

import io.llmnote.companion.enums.Gender;
import io.llmnote.companion.enums.RelationshipType;
import io.llmnote.companion.enums.SpeakingStyle;
import io.llmnote.companion.enums.VoiceStyle;
import org.junit.jupiter.api.Test;

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
