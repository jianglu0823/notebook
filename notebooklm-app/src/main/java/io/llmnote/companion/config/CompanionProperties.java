package io.llmnote.companion.config;

import io.llmnote.companion.enums.VoiceStyle;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "notebooklm.companion")
public class CompanionProperties {

    private String defaultModel = "glm-4.7-flash";
    private int memorySearchK = 5;
    private String asrModel = "paraformer-realtime-v2";
    private TtsVoiceMap ttsVoiceMap = new TtsVoiceMap();
    private int intimacyDailyCap = 50;
    private List<String> visionModels = List.of("glm-4.6v-flash", "glm-4.1v-thinking-flash", "qwen-vl-max");
    private int reminderHours = 2;
    private int contextHistoryLimit = 20;
    private int contextHistoryWindowChars = 4000;
    private long maxUploadBytes = 10L * 1024 * 1024;
    private int maxAttachmentsPerMessage = 4;

    @Data
    public static class TtsVoiceMap {
        private String sweet = "longwan";
        private String magnetic = "longcheng";
        private String young = "longhua";
        private String mature = "longqing";
        private String cute = "longxian";
        private String hoarse = "longfei";
        private String calm = "longjing";

        public String resolve(VoiceStyle style) {
            if (style == null) return sweet;
            return switch (style) {
                case SWEET -> sweet;
                case MAGNETIC -> magnetic;
                case YOUNG -> young;
                case MATURE -> mature;
                case CUTE -> cute;
                case HOARSE -> hoarse;
                case CALM -> calm;
            };
        }
    }
}
