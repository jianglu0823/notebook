package io.llmnote.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "notebooklm")
public class NotebookLmProperties {

    private final Dashscope dashscope = new Dashscope();
    private final Milvus milvus = new Milvus();
    private final Storage storage = new Storage();
    private final Auth auth = new Auth();

    @Data
    public static class Dashscope {
        private String apiKey;
        private String chatModel = "qwen-plus";
        private String vlModel = "qwen-vl-max";
        private String embeddingModel = "text-embedding-v3";
        private String ttsModel = "cosyvoice-v1";
        private String ttsVoiceA = "longwan";
        private String ttsVoiceB = "longcheng";
    }

    @Data
    public static class Milvus {
        private String host = "127.0.0.1";
        private int port = 19530;
        private String collection = "nblm_chunks";
        private int dim = 1024;
    }

    @Data
    public static class Storage {
        private String uploadDir = "./uploads";
        private String audioDir = "./audio";
        private String imageDir = "./images";
    }

    @Data
    public static class Auth {
        private String jwtSecret = "change-me-in-prod-at-least-32-bytes-long-secret";
        private long jwtExpirySeconds = 604_800L;
        private String guestCookieName = "nblm_guest";
    }
}
