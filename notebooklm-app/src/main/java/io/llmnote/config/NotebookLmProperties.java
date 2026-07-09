package io.llmnote.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "notebooklm")
public class NotebookLmProperties {

    private final Dashscope dashscope = new Dashscope();
    private final Milvus milvus = new Milvus();
    private final Storage storage = new Storage();
    private final Auth auth = new Auth();
    private final World world = new World();

    @Data
    public static class Dashscope {
        private String apiKey;
        private String chatModel = "qwen-plus";
        private String vlModel = "qwen-vl-max";
        private String embeddingModel = "text-embedding-v3";
        private String ttsModel = "cosyvoice-v1";
        private String ttsVoiceA = "longwan";
        private String ttsVoiceB = "longcheng";
        /** 可选文本模型及其单价(元/千 token),供会议/写作选用与 token→元 换算。 */
        private List<ModelPricing> pricing = new ArrayList<>();
    }

    /** 单个模型的展示名与商用单价(元 / 1000 token)。 */
    @Data
    public static class ModelPricing {
        private String name;
        private String label;
        private double inputPer1k;
        private double outputPer1k;
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

    /** 智能体小世界(斯坦福小镇模式)配置。 */
    @Data
    public static class World {
        /** 每个员工 HarnessAgent 的 workspace 根目录(记忆落盘,按 emp_<id> 分子目录)。 */
        private String workspaceRoot = "./data/agents";
        /** 自主行动默认模型(最便宜)。 */
        private String autonomousModel = "qwen-turbo";
    }
}
