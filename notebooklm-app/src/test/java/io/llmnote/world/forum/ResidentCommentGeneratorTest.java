package io.llmnote.world.forum;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.llmnote.llm.ChatModelFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * W1 单元测试:ResidentCommentGenerator — 5 类典型输入的后处理 + LLM 超时兜底。
 * 1) 正常返回(去首尾空白);2) think 块剥离;3) 元话语走 fallback;
 * 4) 空返回走 fallback;5) 超长(>200)截断 + 省略号;
 * 6) 首尾是引号 → strip;7) 8s 超时走 fallback;8) persona 为空 → 用默认 persona。
 */
@ExtendWith(MockitoExtension.class)
class ResidentCommentGeneratorTest {

    @Mock ChatModelFactory modelFactory;
    ResidentCommentGenerator generator;

    private static ChatResponse textResp(String s) {
        return ChatResponse.builder()
                .id("t")
                .content(List.of(TextBlock.builder().text(s).build()))
                .build();
    }

    private void newGen() {
        generator = new ResidentCommentGenerator(modelFactory);
    }

    // 1) 正常返回
    @Test
    void normal_returnsTrimmedText() {
        newGen();
        when(modelFactory.streamTextWithFallback(anyString(), any()))
                .thenReturn(List.of(textResp("  哈哈哈下雪真好玩,我上次也踩了一脚  ")));
        String out = generator.generate("热情", "豆豆", "雪", "今天下雪", "日常");
        assertThat(out).isEqualTo("哈哈哈下雪真好玩,我上次也踩了一脚");
    }

    // 2) 包含 think 块
    @Test
    void containsThinkBlock_stripped() {
        newGen();
        when(modelFactory.streamTextWithFallback(anyString(), any()))
                .thenReturn(List.of(textResp("<think>用户想让我写评论</think>下雪真好玩")));
        String out = generator.generate("热情", "豆豆", "雪", "今天下雪", "日常");
        assertThat(out).isEqualTo("下雪真好玩");
    }

    // 3) 元话语 → fallback 文案池
    @Test
    void metaDiscourse_fallsBackToPool() {
        newGen();
        when(modelFactory.streamTextWithFallback(anyString(), any()))
                .thenReturn(List.of(textResp("作为 AI 助手,我觉得这个帖子写得很有深度,反映了作者细腻的观察……")));
        String out = generator.generate("热情", "豆豆", "雪", "今天下雪", "日常");
        assertThat(out).isNotBlank();
        assertThat(out).doesNotContain("AI 助手");
    }

    // 4) 空返回 → fallback
    @Test
    void emptyResponse_fallsBack() {
        newGen();
        when(modelFactory.streamTextWithFallback(anyString(), any()))
                .thenReturn(List.of(textResp("   ")));
        String out = generator.generate("热情", "豆豆", "雪", "今天下雪", "日常");
        assertThat(out).isNotBlank();
    }

    // 5) 超长截断
    @Test
    void overLong_truncatedTo200WithEllipsis() {
        newGen();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 500; i++) sb.append("啊");
        when(modelFactory.streamTextWithFallback(anyString(), any()))
                .thenReturn(List.of(textResp(sb.toString())));
        String out = generator.generate("热情", "豆豆", "雪", "今天下雪", "日常");
        assertThat(out.length()).isLessThanOrEqualTo(201); // 200 + 省略号
        assertThat(out).endsWith("…");
    }

    // 6) 首尾是 " → strip
    @Test
    void quoteMarks_stripped() {
        newGen();
        when(modelFactory.streamTextWithFallback(anyString(), any()))
                .thenReturn(List.of(textResp("\"下雪真好玩\"")));
        String out = generator.generate("热情", "豆豆", "雪", "今天下雪", "日常");
        assertThat(out).isEqualTo("下雪真好玩");
    }

    // 7) 8s 超时 → fallback
    @Test
    void llmTimeout_fallsBack() {
        newGen();
        // 让 streamTextWithFallback 一直阻塞超过 8s
        when(modelFactory.streamTextWithFallback(anyString(), any())).thenAnswer(inv -> {
            Thread.sleep(15_000);
            return List.of(textResp("永远等不到"));
        });
        String out = generator.generate("热情", "豆豆", "雪", "今天下雪", "日常");
        assertThat(out).isNotBlank();
        assertThat(out).doesNotContain("永远等不到");
    }

    // 8) persona 为空 → 用默认 persona 段(走 LLM,无法直接看 prompt,只验不抛 + 有返回)
    @Test
    void emptyPersona_stillReturns() {
        newGen();
        when(modelFactory.streamTextWithFallback(anyString(), any()))
                .thenReturn(List.of(textResp("好看")));
        String out1 = generator.generate(null, "豆豆", "雪", "今天下雪", "日常");
        String out2 = generator.generate("", "豆豆", "雪", "今天下雪", "日常");
        assertThat(out1).isEqualTo("好看");
        assertThat(out2).isEqualTo("好看");
    }
}
