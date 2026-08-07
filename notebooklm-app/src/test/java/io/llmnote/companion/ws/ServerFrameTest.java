package io.llmnote.companion.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** ServerFrame 序列化与合规帧(remedy/AI 身份)字段填充。 */
class ServerFrameTest {

    private final ObjectMapper om = new ObjectMapper();

    @Test
    void reminder_factorySetsKindAndText() throws Exception {
        ServerFrame f = ServerFrame.reminder("ai_identity", "对方是 AI");
        assertThat(f.kind()).isEqualTo("reminder");
        assertThat(f.reminderKind()).isEqualTo("ai_identity");
        assertThat(f.text()).isEqualTo("对方是 AI");
        String json = om.writeValueAsString(f);
        assertThat(json).contains("\"reminderKind\":\"ai_identity\"");
        assertThat(json).contains("\"text\":\"对方是 AI\"");
    }

    @Test
    void milestone_factorySetsKindAndText() throws Exception {
        ServerFrame f = ServerFrame.milestone("milestone_7", "7 天啦");
        assertThat(f.kind()).isEqualTo("milestone");
        assertThat(f.milestoneKind()).isEqualTo("milestone_7");
        assertThat(f.text()).isEqualTo("7 天啦");
    }

    @Test
    void chatDone_serializesAllUsageFields() throws Exception {
        ServerFrame f = ServerFrame.done(11L, "hi", "glm-4.7-flash", 100L, 50L, "req-1");
        String json = om.writeValueAsString(f);
        assertThat(json).contains("\"kind\":\"chat.done\"");
        assertThat(json).contains("\"messageId\":11");
        assertThat(json).contains("\"inputTokens\":100");
        assertThat(json).contains("\"outputTokens\":50");
    }
}
