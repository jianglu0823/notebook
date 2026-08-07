package io.llmnote.companion;

import io.llmnote.companion.config.CompanionProperties;
import io.llmnote.llm.ChatModelFactory;
import org.springframework.stereotype.Service;

/**
 * 伴侣视觉模型选择(基于伴侣 defaultModel 派系)。
 * - GLM 系(glm-* / cog-*)→ glm-4.6v-flash(智谱免费多模态)
 * - Qwen 系(qwen-*)→ qwen-vl-max(千问付费视觉)
 * - 其他(未知)→ 第一个 visionModels 候选
 * C8 明确:chat.text 帧临时 model 字段不覆盖视觉模型选择,故此处只依据伴侣 defaultModel。
 */
@Service
public class CompanionVisionService {

    private final ChatModelFactory modelFactory;
    private final CompanionProperties properties;

    public CompanionVisionService(ChatModelFactory modelFactory, CompanionProperties properties) {
        this.modelFactory = modelFactory;
        this.properties = properties;
    }

    /** 选视觉模型名(已 normalize)。 */
    public String pickVisionModel(String companionDefaultModel) {
        String m = companionDefaultModel == null ? "" : companionDefaultModel.toLowerCase();
        if (m.startsWith("glm-") || m.startsWith("cog-")) {
            return "glm-4.6v-flash";
        }
        if (m.startsWith("qwen-")) {
            return "qwen-vl-max";
        }
        var fallbacks = properties.getVisionModels();
        return fallbacks.isEmpty() ? "glm-4.6v-flash" : fallbacks.get(0);
    }
}
