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

/**
 * 作者「偶尔回一嘴」生成器:用 LLM 基于作者 persona + 收到的评论 + 帖子信息
 * 生成 1-2 句口吻与作者一致的回复。与 {@link ResidentCommentGenerator} 共用模型 + 后处理思路,
 * 但 prompt 侧重「回应某条具体评论」而非「对帖子泛评」。8s 超时,失败 fallback。
 */
@Slf4j
@Component
public class AuthorReplyGenerator {

    static final long TIMEOUT_MS = 8_000L;
    static final int MAX_CHARS = 200;
    static final String MODEL = "qwen-turbo";

    private static final List<String> FALLBACK_POOL = List.of(
            "谢啦",
            "哈哈好",
            "嗯嗯",
            "说到心坎里",
            "我也这么觉得",
            "过奖了",
            "一起继续",
            "谢谢捧场",
            "改天再写",
            "下次聊"
    );

    private final ChatModelFactory modelFactory;

    public AuthorReplyGenerator(ChatModelFactory modelFactory) {
        this.modelFactory = modelFactory;
    }

    public String generate(String authorPersona, String commentText,
                           String postTitle, String postContent) {
        String prompt = buildPrompt(authorPersona, commentText, postTitle, postContent);
        List<Msg> messages = List.of(
                Msg.builder().role(MsgRole.USER).content(TextBlock.builder().text(prompt).build()).build());
        String raw;
        try {
            raw = CompletableFuture.supplyAsync(() -> callLlm(messages))
                    .get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            log.warn("author reply gen timeout, fallback to pool");
            return pickFallback();
        } catch (Exception ex) {
            log.warn("author reply gen failed: {}", ex.getMessage());
            return pickFallback();
        }
        return ResidentCommentGenerator.postProcess(raw, this::pickFallback);
    }

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

    private String buildPrompt(String authorPersona, String commentText, String postTitle, String postContent) {
        String p = (authorPersona == null || authorPersona.isBlank())
                ? ResidentCommentGenerator.DEFAULT_PERSONA : authorPersona;
        return "你是论坛帖子的原作者,有一位读者给你留言了,请用 1-2 句简短回复他(她)。\n"
                + "你的人设:" + p + "\n"
                + "你的帖子标题:" + (postTitle == null ? "" : postTitle) + "\n"
                + "你的帖子正文:" + (postContent == null ? "" : postContent) + "\n"
                + "读者的留言:" + (commentText == null ? "" : commentText) + "\n\n"
                + "硬约束:\n"
                + "- 1-2 句话,总长不超过 80 字\n"
                + "- 像真人作者回应,口吻与你的人设一致,不要官方\n"
                + "- 不要复述对方的话、不要分点、不要加引号、不要解释\n"
                + "- 直接给出回复文字,不要任何前缀";
    }

    private String pickFallback() {
        long idx = (System.nanoTime() / 1000) % FALLBACK_POOL.size();
        return FALLBACK_POOL.get((int) Math.abs(idx));
    }
}
