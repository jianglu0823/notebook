package io.llmnote.llm;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.DashScopeChatModel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/** 对 DashScopeChatModel 的薄封装:阻塞式取完整文本,供各生成场景复用。 */
@Component
@RequiredArgsConstructor
public class ChatCompletion {

    private final DashScopeChatModel chatModel;

    /** 单轮 system+user 提示,阻塞返回完整文本。 */
    public String complete(String system, String user) {
        List<Msg> messages = List.of(
                Msg.builder().role(MsgRole.SYSTEM).content(TextBlock.builder().text(system).build()).build(),
                Msg.builder().role(MsgRole.USER).content(TextBlock.builder().text(user).build()).build());
        return complete(messages);
    }

    public String complete(List<Msg> messages) {
        List<ChatResponse> responses = chatModel.stream(messages, List.of(), null)
                .collectList().block();
        StringBuilder sb = new StringBuilder();
        if (responses != null) {
            for (ChatResponse r : responses) {
                if (r.getContent() == null) continue;
                r.getContent().forEach(b -> {
                    if (b instanceof TextBlock tb && tb.getText() != null) sb.append(tb.getText());
                });
            }
        }
        return sb.toString();
    }
}
