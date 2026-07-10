package io.llmnote.llm;

import io.llmnote.config.NotebookLmProperties.ModelPricing;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 计费说明入口:返回①可选模型的单价(会议/写作的模型 chips 与费用换算都读它)
 * ②各功能 ↔ 所用模型的对照表(功能清单硬编码于此,随代码演进维护)。
 */
@RestController
@RequestMapping("/api/pricing")
@RequiredArgsConstructor
public class PricingController {

    private final ChatModelFactory modelFactory;

    @GetMapping
    public Map<String, Object> pricing() {
        List<Map<String, Object>> models = modelFactory.pricing().stream()
                .map(p -> Map.<String, Object>of(
                        "name", p.getName(),
                        "label", p.getLabel(),
                        "inputPer1k", p.getInputPer1k(),
                        "outputPer1k", p.getOutputPer1k()))
                .toList();

        List<Map<String, Object>> features = List.of(
                feature("智能问答 QA", "qwen-plus", "文档问答,固定模型"),
                feature("摘要 / 学习指南 / FAQ", "qwen-plus", "生成类,固定模型"),
                feature("新闻联网整理", "qwen-plus", "enable_search 联网,固定模型"),
                feature("播客脚本", "qwen-plus", "固定模型"),
                feature("小红书文案", "qwen-plus", "固定模型"),
                feature("协作写作", "可选(默认 qwen-plus)", "发起时可选四档模型,按用量计费"),
                feature("智能体会议", "可选(默认 qwen-plus)", "发起时可选四档模型,按用量计费"),
                feature("图片理解", "qwen-vl-max", "多模态,固定模型"),
                feature("向量嵌入 (RAG)", "text-embedding-v3", "检索用,单独计价"));

        return Map.of("models", models, "features", features);
    }

    private static Map<String, Object> feature(String name, String model, String note) {
        return Map.of("name", name, "model", model, "note", note);
    }
}
