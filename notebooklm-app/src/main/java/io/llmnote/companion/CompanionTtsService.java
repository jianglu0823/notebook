package io.llmnote.companion;

import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesisParam;
import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesizer;
import io.llmnote.companion.config.CompanionProperties;
import io.llmnote.config.NotebookLmProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 伴侣 TTS 服务(DashScope cosyvoice-v1 同步调用)。
 * - 按句切分([。！？!?\n]);单句独立调用,失败不阻断整段(R11)。
 * - 输出 MP3 字节;每段携带 0-based 句序号 + 配音音色。
 */
@Slf4j
@Service
public class CompanionTtsService {

    private static final Pattern SENTENCE_SPLIT = Pattern.compile("(?<=[。！？!?\\n])");

    private final NotebookLmProperties props;
    private final CompanionProperties properties;

    public CompanionTtsService(NotebookLmProperties props, CompanionProperties properties) {
        this.props = props;
        this.properties = properties;
    }

    public record Sentence(int seq, String text, byte[] mp3, boolean failed) {}

    /**
     * 同步合成所有句子(失败句占位 failed=true,空字节)。
     * 失败原因:DashScope 401/403/限流/超时,均不抛给上层(由调用方决定是否推错误帧)。
     */
    public List<Sentence> synthesizeAll(String fullText, String voice) {
        List<String> sentences = split(fullText);
        if (sentences.isEmpty()) return List.of();
        List<Sentence> out = new ArrayList<>(sentences.size());
        String v = voice == null || voice.isBlank() ? properties.getTtsVoiceMap().getSweet() : voice;
        for (int i = 0; i < sentences.size(); i++) {
            String s = sentences.get(i);
            try {
                SpeechSynthesisParam param = SpeechSynthesisParam.builder()
                        .apiKey(props.getDashscope().getApiKey())
                        .model(props.getDashscope().getTtsModel())
                        .voice(v)
                        .build();
                SpeechSynthesizer synth = new SpeechSynthesizer(param, null);
                ByteBuffer buf = synth.call(s);
                byte[] bytes = new byte[buf.remaining()];
                buf.get(bytes);
                out.add(new Sentence(i, s, bytes, false));
            } catch (Exception e) {
                log.warn("TTS 句 {} 失败(已跳过): {}", i, e.getMessage());
                out.add(new Sentence(i, s, new byte[0], true));
            }
        }
        return out;
    }

    public static List<String> split(String text) {
        if (text == null || text.isBlank()) return List.of();
        Matcher m = SENTENCE_SPLIT.matcher(text);
        List<String> out = new ArrayList<>();
        int start = 0;
        while (m.find()) {
            int end = m.end();
            if (end > start) {
                String seg = text.substring(start, end).trim();
                if (!seg.isEmpty()) out.add(seg);
            }
            start = end;
        }
        if (start < text.length()) {
            String tail = text.substring(start).trim();
            if (!tail.isEmpty()) out.add(tail);
        }
        return out;
    }
}
