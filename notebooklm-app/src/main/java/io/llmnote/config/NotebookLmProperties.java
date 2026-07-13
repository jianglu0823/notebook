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
    private final Zhipu zhipu = new Zhipu();
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

    /** 智谱开放平台(OpenAI 兼容)免费模型配置:GLM 文本/多模态 + CogView 图片 + CogVideoX 视频。 */
    @Data
    public static class Zhipu {
        private String apiKey;
        private String baseUrl = "https://open.bigmodel.cn/api/paas/v4";
        private String textModel = "glm-4.7-flash";
        private String vlModel = "glm-4.6v-flash";
        private String imageModel = "cogview-3-flash";
        private String videoModel = "cogvideox-flash";
        /** 推理模型(用于一生回顾悼词、沙盒世界报告等需要归纳/反思的生成)。 */
        private String reasoningModel = "glm-z1-flash";
        /** 主模型调用失败时的备用降级链(按序尝试),都是免费文本模型。 */
        private List<String> fallbackModels = new ArrayList<>();
        /** 免费文本模型定价(单价 0),用于前端可选项展示与 normalize 放行。 */
        private List<ModelPricing> pricing = new ArrayList<>();
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

    /** 智能体小世界(智能体小镇模式)配置。 */
    @Data
    public static class World {
        /** 每个员工 HarnessAgent 的 workspace 根目录(记忆落盘,按 emp_<id> 分子目录)。 */
        private String workspaceRoot = "./data/agents";
        /** 小镇统一默认文本模型(自主行动/1:1/产物/日报/沙盒)。默认智谱免费 GLM。 */
        private String textModel = "glm-4.7-flash";
        /** 自主行动默认模型(免费)。 */
        private String autonomousModel = "glm-4.7-flash";
        /** 对话捏人模型:用千问 flash(付费但便宜快),对话补全更稳。 */
        private String builderModel = "qwen-flash";
    }
}
