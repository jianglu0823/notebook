package io.llmnote.llm;

import io.agentscope.core.model.DashScopeChatModel;
import io.llmnote.config.NotebookLmProperties;
import io.llmnote.config.NotebookLmProperties.ModelPricing;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按模型名取(建)DashScopeChatModel 的工厂,并提供 token→元 的费用换算。
 * 只允许配置里 pricing 列出的模型(会议/写作的可选项);未知名回退默认 chatModel。
 * 每个模型只建一次并缓存,避免每场会议都 new。
 */
@Component
public class ChatModelFactory {

    private final NotebookLmProperties props;
    private final DashScopeChatModel defaultModel;
    private final ConcurrentHashMap<String, DashScopeChatModel> cache = new ConcurrentHashMap<>();

    public ChatModelFactory(NotebookLmProperties props, DashScopeChatModel chatModel) {
        this.props = props;
        this.defaultModel = chatModel;
    }

    /** 允许选用的模型定价列表(即前端可选项)。 */
    public List<ModelPricing> pricing() {
        return props.getDashscope().getPricing();
    }

    /** 校验并规范化模型名:不在允许列表则回退默认(qwen-plus)。 */
    public String normalize(String name) {
        if (name == null || name.isBlank()) return props.getDashscope().getChatModel();
        for (ModelPricing p : pricing()) {
            if (p.getName().equalsIgnoreCase(name.trim())) return p.getName();
        }
        return props.getDashscope().getChatModel();
    }

    /** 取指定模型的 DashScopeChatModel(带缓存);未知名回退默认 bean。 */
    public DashScopeChatModel forModel(String name) {
        String m = normalize(name);
        if (m.equals(props.getDashscope().getChatModel())) return defaultModel;
        return cache.computeIfAbsent(m, key -> DashScopeChatModel.builder()
                .apiKey(props.getDashscope().getApiKey())
                .modelName(key)
                .stream(true)
                .build());
    }

    /** 按模型单价把 token 数换算成人民币元。未知模型按默认档估。 */
    public double costRmb(String model, long inputTokens, long outputTokens) {
        ModelPricing p = pricingOf(model);
        if (p == null) return 0d;
        return inputTokens / 1000d * p.getInputPer1k()
                + outputTokens / 1000d * p.getOutputPer1k();
    }

    private ModelPricing pricingOf(String model) {
        String m = normalize(model);
        for (ModelPricing p : pricing()) {
            if (p.getName().equals(m)) return p;
        }
        return null;
    }
}
