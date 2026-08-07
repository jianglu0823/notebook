package io.llmnote.companion.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** ClientFrame 反序列化:语音回复字段 + 必填字段映射。 */
class ClientFrameTest {

    private final ObjectMapper om = new ObjectMapper();

    @Test
    void chatText_withVoiceReplyTrue() throws Exception {
        String json = "{\"kind\":\"chat.text\",\"companionId\":7,\"content\":\"hi\",\"model\":\"glm-4.7-flash\",\"voiceReply\":true}";
        ClientFrame f = om.readValue(json, ClientFrame.class);
        assertThat(f.kind()).isEqualTo("chat.text");
        assertThat(f.companionId()).isEqualTo(7L);
        assertThat(f.content()).isEqualTo("hi");
        assertThat(f.model()).isEqualTo("glm-4.7-flash");
        assertThat(f.voiceReply()).isTrue();
    }

    @Test
    void voiceStart_frame() throws Exception {
        String json = "{\"kind\":\"voice.start\",\"companionId\":7,\"voiceReply\":false}";
        ClientFrame f = om.readValue(json, ClientFrame.class);
        assertThat(f.kind()).isEqualTo("voice.start");
        assertThat(f.companionId()).isEqualTo(7L);
        assertThat(f.voiceReply()).isFalse();
    }

    @Test
    void missingVoiceReply_defaultsToNull() throws Exception {
        String json = "{\"kind\":\"chat.text\",\"companionId\":7,\"content\":\"hi\"}";
        ClientFrame f = om.readValue(json, ClientFrame.class);
        assertThat(f.voiceReply()).isNull();
    }
}
