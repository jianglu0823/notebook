package io.llmnote.world.forum;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.llmnote.llm.ChatModelFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * 论坛「居民随机路过」评论生成器:用 LLM 基于 persona + 帖子信息生成 1-3 句中文短评。
 * 后处理链:strip think 块 → 截首尾空白 → 截断 200 字 + 省略号 → 去首尾成对引号 →
 * 检测元话语/空白 → fallback 到预置文案池。整个调用有 8s 超时,绝不阻塞调度。
 */
@Slf4j
@Component
public class ResidentCommentGenerator {

    /** LLM 调用超时(覆盖模型降级链 + 慢响应)。 */
    static final long TIMEOUT_MS = 8_000L;

    /** 输出文本上限。超过则截断 + 省略号。 */
    static final int MAX_CHARS = 200;

    /** 评论生成统一走 qwen-turbo(便宜快,免费层配额够用)。 */
    static final String MODEL = "qwen-turbo";

    /** persona 为空时的默认人格段(让 prompt 不会因 null 报错)。 */
    static final String DEFAULT_PERSONA = "性格随和的小镇居民,口语化,不端着。";

    private static final List<String> FALLBACK_POOL = List.of(
            "哈哈哈有意思",
            "路过看看",
            "这活儿不错",
            "说的在理",
            "我也这么觉得",
            "真不错",
            "有被戳到",
            "记下了",
            "围观",
            "笑死",
            "老哥稳",
            "好家伙",
            "这谁顶得住",
            "这波我站你",
            "666",
            "下回带上我",
            "镇上的事儿我都知道",
            "过来人,懂的",
            "看着就开心",
            "得闲一起"
    );

    private final ChatModelFactory modelFactory;

    public ResidentCommentGenerator(ChatModelFactory modelFactory) {
        this.modelFactory = modelFactory;
    }

    /**
     * 生成一条评论。返回 1-200 字,失败/超时走 fallback 池。
     */
    public String generate(String persona, String authorName, String title, String content, String catLabel) {
        String prompt = buildPrompt(persona, authorName, title, content, catLabel);
        List<Msg> messages = List.of(
                Msg.builder().role(MsgRole.USER).content(TextBlock.builder().text(prompt).build()).build());

        String raw;
        try {
            raw = CompletableFuture.supplyAsync(() -> callLlm(messages))
                    .get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            log.warn("resident comment gen timeout, fallback to pool");
            return pickFallback();
        } catch (Exception ex) {
            log.warn("resident comment gen failed: {}", ex.getMessage());
            return pickFallback();
        }
        return postProcess(raw);
    }

    /** 实际 LLM 调用:取最后一段 text,strip think 块。 */
    private String callLlm(List<Msg> messages) {
        List<ChatResponse> responses = modelFactory.streamTextWithFallback(MODEL, messages);
        if (responses == null || responses.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (ChatResponse r : responses) {
            if (r.getContent() == null) continue;
            r.getContent().forEach(b -> {
                if (b instanceof TextBlock tb && tb.getText() != null) sb.append(tb.getText());
            });
        }
        return ChatModelFactory.stripThink(sb.toString());
    }

    /** 严格约束的 prompt:1-3 句 / 不超 80 字 / 口语 / 不加前缀 / 不解释。 */
    private String buildPrompt(String persona, String authorName, String title, String content, String catLabel) {
        String p = (persona == null || persona.isBlank()) ? DEFAULT_PERSONA : persona;
        return "你正以一个「小镇居民」的身份路过论坛,准备对一篇帖子说一两句简短评论。\n"
                + "你的人设:" + p + "\n"
                + "你扮演的名字:" + authorName + "。\n"
                + "帖子类目:" + (catLabel == null ? "日常" : catLabel) + "\n"
                + "帖子标题:" + (title == null ? "" : title) + "\n"
                + "帖子正文:" + (content == null ? "" : content) + "\n\n"
                + "硬约束:\n"
                + "- 只输出 1-3 句话,总长不超过 80 字\n"
                + "- 像真人邻居路过随口说一句,口语、有温度\n"
                + "- 严禁以「作为 AI 助手」「我觉得」「从专业的角度」开头\n"
                + "- 不要复述帖子内容、不要分点、不要加引号、不要解释\n"
                + "- 直接给出评论文字,不要任何前缀";
    }

    /** 后处理:strip think → trim → 截断 → 去引号 → 兜底。 */
    String postProcess(String raw) {
        return postProcess(raw, this::pickFallback);
    }

    /** 共享后处理:strip think → trim → 截断 → 去引号 → 兜底。fallback 由调用方提供。 */
    public static String postProcess(String raw, Supplier<String> fallback) {
        if (raw == null) return fallback.get();
        String s = raw.trim();
        if (s.isEmpty()) return fallback.get();
        if (looksLikeMeta(s)) return fallback.get();
        if (s.length() > MAX_CHARS) {
            s = s.substring(0, MAX_CHARS) + "…";
        }
        s = stripQuoteMarks(s);
        if (s.isEmpty()) return fallback.get();
        return s;
    }

    /** 检测明显的"AI 助手"元话语开头(中文/英文/常见变体)。 */
    private static boolean looksLikeMeta(String s) {
        String low = s.toLowerCase();
        return low.startsWith("作为 ai")
                || low.startsWith("作为一个 ai")
                || low.startsWith("作为助手")
                || low.startsWith("作为一个助手")
                || low.startsWith("as an ai")
                || low.startsWith("i am an ai")
                || low.startsWith("i'm an ai")
                || low.startsWith("从专业的角度")
                || low.startsWith("作为一名")
                || low.startsWith("作为一个");
    }

    /** 去掉首尾成对的 " 或 "。 */
    static String stripQuoteMarks(String s) {
        if (s == null || s.length() < 2) return s == null ? "" : s;
        char first = s.charAt(0);
        char last = s.charAt(s.length() - 1);
        if ((first == '"' && last == '"')
                || (first == '"' && last == '"')) {
            return s.substring(1, s.length() - 1).trim();
        }
        return s;
    }

    /** 简单时间戳散列选 fallback:同 tick 内尽量不重样,跨 tick 也会滚动。 */
    private String pickFallback() {
        long idx = (System.nanoTime() / 1000) % FALLBACK_POOL.size();
        return FALLBACK_POOL.get((int) Math.abs(idx));
    }
}
