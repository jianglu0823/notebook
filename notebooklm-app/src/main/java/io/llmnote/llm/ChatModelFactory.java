package io.llmnote.llm;

import io.agentscope.core.formatter.openai.GLMFormatter;
import io.agentscope.core.formatter.openai.OpenAIChatFormatter;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.model.ToolSchema;
import io.llmnote.config.NotebookLmProperties;
import io.llmnote.config.NotebookLmProperties.ModelPricing;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 按模型名取(建)聊天模型的工厂,并提供 token→元 的费用换算。
 * 支持两类后端:智谱 GLM(OpenAI 兼容,免费)与 DashScope qwen(付费)。默认走智谱免费 GLM。
 * 只允许配置里 pricing 列出的模型;未知名回退默认(小镇文本模型 = glm-4.5-flash)。
 * 每个模型只建一次并缓存,避免每场会议都 new。
 */
@Slf4j
@Component
public class ChatModelFactory {

    /** 免费模型限流重试次数与退避基数。 */
    private static final int MAX_RETRIES = 4;
    private static final long BACKOFF_BASE_MS = 800;

    private final NotebookLmProperties props;
    private final ConcurrentHashMap<String, ChatModelBase> cache = new ConcurrentHashMap<>();

    public ChatModelFactory(NotebookLmProperties props) {
        this.props = props;
    }

    /** 合并可选模型定价列表(GLM 免费在前 + qwen 付费)。即前端可选项。 */
    public List<ModelPricing> pricing() {
        List<ModelPricing> all = new ArrayList<>(props.getZhipu().getPricing());
        all.addAll(props.getDashscope().getPricing());
        return all;
    }

    /** 小镇统一默认文本模型(glm-4.5-flash)。 */
    public String defaultTextModel() {
        return props.getWorld().getTextModel();
    }

    /** 推理模型(用于一生回顾悼词、沙盒世界报告等需要归纳/反思的生成)。 */
    public String reasoningModel() {
        String m = props.getZhipu().getReasoningModel();
        return (m == null || m.isBlank()) ? defaultTextModel() : m;
    }

    /** 对话捏人模型(千问 flash,便宜快)。 */
    public String builderModel() {
        String m = props.getWorld().getBuilderModel();
        return (m == null || m.isBlank()) ? defaultTextModel() : m;
    }

    /**
     * 工具调用场景默认模型(支持 function calling)。
     * 历史用过 qwen-turbo(便宜),但实测在 GLM 429 降级链后 qwen-turbo 的 tool-call 稳定性骤降
     * (8 次中 7 次 toolUses=0,模型直接吐"成功啦"幻觉),qwen-plus 同样支持 function calling
     * 且指令遵循稳定一档。智谱免费 GLM 系列(glm-4.7-flash / glm-4-flash-250414 / glm-4.5-flash)
     * 对 function calling 不可靠,降级链必须固定在 tools-capable 名单内,绝不能落到 GLM。
     */
    public static final String TOOLS_CAPABLE_DEFAULT = "qwen-plus";

    /** 已确认支持 function calling 的模型名集合(用于 fallback 链过滤)。 */
    private static final java.util.Set<String> TOOLS_CAPABLE = java.util.Set.of(
            "qwen-turbo", "qwen-plus", "qwen-max", "qwen-flash", "qwen-long");

    /** 工具调用场景默认模型:固定走 DashScope qwen-plus(支持 function calling,稳定性高于 turbo)。 */
    public String toolsCapableModel() {
        return TOOLS_CAPABLE_DEFAULT;
    }

    /** 校验并规范化模型名:不在允许列表则回退默认(GLM Flash)。 */
    public String normalize(String name) {
        if (name == null || name.isBlank()) return defaultTextModel();
        for (ModelPricing p : pricing()) {
            if (p.getName().equalsIgnoreCase(name.trim())) return p.getName();
        }
        return defaultTextModel();
    }

    /** 取指定模型(带缓存);未知名回退默认。GLM 用 OpenAIChatModel,qwen 用 DashScopeChatModel。 */
    public ChatModelBase forModel(String name) {
        String m = normalize(name);
        return cache.computeIfAbsent(m, this::build);
    }

    private ChatModelBase build(String modelName) {
        if (isZhipu(modelName)) {
            return OpenAIChatModel.builder()
                    .apiKey(props.getZhipu().getApiKey())
                    .baseUrl(props.getZhipu().getBaseUrl())
                    .modelName(modelName)
                    .formatter(new GLMFormatter())
                    .stream(true)
                    .build();
        }
        // DashScope 文本模型走 OpenAI 兼容端点 + OpenAIChatFormatter:
        // 原生 DashScopeChatModel + DashScopeChatFormatter 解析 qwen function calling 不可靠
        // (实测 input 字段被丢掉,L0 仅回 tool_use name + 16 个 __fragment__ 噪声)。
        String oaBase = props.getDashscope().getOpenaiCompatibleBaseUrl();
        if (oaBase == null || oaBase.isBlank()) {
            log.warn("dashscope.openai-compatible-base-url 未配置,回退原生 DashScope SDK(不推荐,function calling 解析有问题)");
            return DashScopeChatModel.builder()
                    .apiKey(props.getDashscope().getApiKey())
                    .modelName(modelName)
                    .stream(true)
                    .build();
        }
        return OpenAIChatModel.builder()
                .apiKey(props.getDashscope().getApiKey())
                .baseUrl(oaBase)
                .modelName(modelName)
                .formatter(new OpenAIChatFormatter())
                .stream(true)
                .build();
    }

    private boolean isZhipu(String modelName) {
        for (ModelPricing p : props.getZhipu().getPricing()) {
            if (p.getName().equalsIgnoreCase(modelName)) return true;
        }
        return false;
    }

    /**
     * 阻塞式取一次文本补全的响应块;免费模型有速率限制,遇限流/瞬时错误按指数退避重试(仅重试,不回退)。
     * 全部重试耗尽仍失败则抛出原异常,由调用方降级处理。
     */
    public List<ChatResponse> streamText(ChatModelBase model, List<Msg> messages) {
        return streamText(model, messages, null, List.of());
    }

    /**
     * 同 {@link #streamText(ChatModelBase, List)},但透传 GenerateOptions(如 max_tokens)。
     * options=null 时行为与无参重载完全一致。
     */
    public List<ChatResponse> streamText(ChatModelBase model, List<Msg> messages, GenerateOptions options) {
        return streamText(model, messages, options, List.of());
    }

    /**
     * 同 {@link #streamText(ChatModelBase, List, GenerateOptions)},并把工具 schema 列表传给模型
     * (供 ReAct 循环使用:第一轮 stream 时告知模型可用工具,工具结果回灌后再次 stream 可省略 tools)。
     */
    public List<ChatResponse> streamText(ChatModelBase model, List<Msg> messages, GenerateOptions options, List<io.agentscope.core.model.ToolSchema> tools) {
        RuntimeException last = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                return model.stream(messages, tools == null ? List.of() : tools, options).collectList().block();
            } catch (RuntimeException ex) {
                last = ex;
                if (attempt >= MAX_RETRIES || !isTransient(ex)) throw ex;
                long sleep = (BACKOFF_BASE_MS << attempt) + ThreadLocalRandom.current().nextInt(400);
                log.warn("GLM 调用限流/瞬时错误,第 {} 次重试,退避 {}ms: {}", attempt + 1, sleep, ex.getMessage());
                try { Thread.sleep(sleep); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw ex;
                }
            }
        }
        throw last; // 理论上不可达
    }

    /**
     * 带模型降级的文本补全:先用 primary,若整套重试仍失败,按配置的 fallbackModels 依次换免费模型再试。
     * 全部候选都失败才抛最后一个异常。返回 ChatResponse 列表(与 streamText 一致)。
     */
    public List<ChatResponse> streamTextWithFallback(String primaryModel, List<Msg> messages) {
        return streamTextWithFallback(primaryModel, messages, null, List.of());
    }

    /**
     * 同 {@link #streamTextWithFallback(String, List)},但把 GenerateOptions 透传给降级链上每个候选模型。
     * options=null 时行为与无参重载完全一致。
     */
    public List<ChatResponse> streamTextWithFallback(String primaryModel, List<Msg> messages, GenerateOptions options) {
        return streamTextWithFallback(primaryModel, messages, options, List.of());
    }

    /**
     * 带工具的降级流式调用:第一轮 stream 传入 ToolSchema,工具执行后再调时通常 tools=空。
     */
    public List<ChatResponse> streamTextWithFallback(String primaryModel, List<Msg> messages, GenerateOptions options, List<io.agentscope.core.model.ToolSchema> tools) {
        List<String> chain = new ArrayList<>();
        String primary = normalize(primaryModel);
        chain.add(primary);
        for (String fb : props.getZhipu().getFallbackModels()) {
            String n = normalize(fb);
            if (!chain.contains(n)) chain.add(n);
        }
        RuntimeException last = null;
        for (int i = 0; i < chain.size(); i++) {
            String m = chain.get(i);
            try {
                return streamText(forModel(m), messages, options, tools);
            } catch (RuntimeException ex) {
                last = ex;
                if (i + 1 < chain.size()) {
                    log.warn("模型 {} 调用失败,降级到 {}: {}", m, chain.get(i + 1), ex.getMessage());
                } else {
                    log.error("模型降级链全部失败({}): {}", chain, ex.getMessage());
                }
            }
        }
        throw last;
    }

    /** 去掉推理模型输出里的 <think>...</think> 思考块,只留最终答案。 */
    public static String stripThink(String text) {
        if (text == null || text.isEmpty()) return text;
        return text.replaceAll("(?is)<think>.*?</think>", "").trim();
    }

    /** 识别可重试错误:限流(429)、超时、连接瞬断等。 */
    private boolean isTransient(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            String msg = t.getMessage();
            if (msg != null) {
                String s = msg.toLowerCase();
                if (s.contains("429") || s.contains("rate") || s.contains("limit")
                        || s.contains("timeout") || s.contains("timed out")
                        || s.contains("too many") || s.contains("503") || s.contains("502")
                        || s.contains("connection reset") || s.contains("overload")) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 已知支持 function calling 的模型名(大小写不敏感)。 */
    public boolean isToolsCapable(String name) {
        if (name == null) return false;
        return TOOLS_CAPABLE.contains(name.trim().toLowerCase());
    }

    /**
     * 工具调用场景的流式降级:主模型若非 tools-capable 则强制换成 toolsCapableModel();
     * 降级链只含已知 tools-capable 模型,绝不落到 GLM(不会调工具,会编结果)。
     * 全部失败抛原异常。
     */
    public List<ChatResponse> streamTextWithToolsFallback(String primaryModel, List<Msg> messages,
                                                            GenerateOptions options, List<ToolSchema> tools) {
        String primary = normalize(primaryModel);
        if (!isToolsCapable(primary)) {
            log.info("工具调用主模型 {} 不在 tools-capable 名单,强制换成 {}", primary, toolsCapableModel());
            primary = toolsCapableModel();
        }
        // 降级链 = 主(若 tools-capable) + toolsCapableModel 兜底
        List<String> chain = new ArrayList<>();
        chain.add(primary);
        if (!chain.contains(toolsCapableModel())) chain.add(toolsCapableModel());
        RuntimeException last = null;
        for (int i = 0; i < chain.size(); i++) {
            String m = chain.get(i);
            try {
                return streamText(forModel(m), messages, options, tools);
            } catch (RuntimeException ex) {
                last = ex;
                if (i + 1 < chain.size()) {
                    log.warn("tools-capable 模型 {} 调用失败,降级到 {}: {}", m, chain.get(i + 1), ex.getMessage());
                } else {
                    log.error("tools-capable 模型链全部失败({}): {}", chain, ex.getMessage());
                }
            }
        }
        throw last;
    }

    /** 按模型单价把 token 数换算成人民币元。免费模型返回 0。 */
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
