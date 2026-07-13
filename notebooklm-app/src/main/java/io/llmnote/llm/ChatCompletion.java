package io.llmnote.llm;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/** 对聊天模型的薄封装:阻塞式取完整文本,供各生成场景复用。默认走小镇文本模型(GLM 免费)。 */
@Component
@RequiredArgsConstructor
public class ChatCompletion {

    private final ChatModelFactory modelFactory;

    /** 单轮 system+user 提示,阻塞返回完整文本。 */
    public String complete(String system, String user) {
        return completeWithUsage(system, user, null).text();
    }

    public String complete(List<Msg> messages) {
        return completeWithUsage(messages, null).text();
    }

    /** 指定模型的单轮补全,返回文本 + token 用量。model 传 null 用默认。 */
    public Result completeWithUsage(String system, String user, ChatModelBase model) {
        List<Msg> messages = List.of(
                Msg.builder().role(MsgRole.SYSTEM).content(TextBlock.builder().text(system).build()).build(),
                Msg.builder().role(MsgRole.USER).content(TextBlock.builder().text(user).build()).build());
        return completeWithUsage(messages, model);
    }

    public Result completeWithUsage(List<Msg> messages, ChatModelBase model) {
        ChatModelBase m = model == null ? modelFactory.forModel(null) : model;
        List<ChatResponse> responses = modelFactory.streamText(m, messages);
        StringBuilder sb = new StringBuilder();
        long in = 0, out = 0;
        if (responses != null) {
            for (ChatResponse r : responses) {
                if (r.getContent() != null) {
                    r.getContent().forEach(b -> {
                        if (b instanceof TextBlock tb && tb.getText() != null) sb.append(tb.getText());
                    });
                }
                // 流式下 usage 为累计值,最后一个非空块即总量,故取最后一次覆盖而非求和
                ChatUsage u = r.getUsage();
                if (u != null) { in = u.getInputTokens(); out = u.getOutputTokens(); }
            }
        }
        return new Result(sb.toString(), in, out);
    }

    /** 补全结果:文本 + 输入/输出 token 数。 */
    public record Result(String text, long inputTokens, long outputTokens) {}
}
