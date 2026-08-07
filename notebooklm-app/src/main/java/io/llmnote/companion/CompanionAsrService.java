package io.llmnote.companion;

import com.alibaba.dashscope.audio.asr.recognition.Recognition;
import com.alibaba.dashscope.audio.asr.recognition.RecognitionParam;
import com.alibaba.dashscope.audio.asr.recognition.RecognitionResult;
import com.alibaba.dashscope.common.ResultCallback;
import io.llmnote.config.NotebookLmProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;

/**
 * 伴侣 ASR 服务:per-session 流式识别(paraformer-realtime-v2 / pcm / 16k)。
 * - start(callback):起识别 stream,中间结果 / 最终结果通过回调上抛。
 * - sendFrame(buf):转发浏览器 PCM 二进制帧。
 * - stop():结束识别,回调收到 onComplete。
 * 复用 io.llmnote.voice.VoiceWsHandler 的同款模式;callback 由 WS 处理器在合适的线程转成 ServerFrame 推送。
 */
@Slf4j
@Service
public class CompanionAsrService {

    private static final String ASR_MODEL = "paraformer-realtime-v2";
    private static final int SAMPLE_RATE = 16000;

    private final NotebookLmProperties props;

    public CompanionAsrService(NotebookLmProperties props) {
        this.props = props;
    }

    public interface RecognitionCallback {
        /** 中间或最终句;finalFlag 由 DashScope 决定。 */
        void onText(String text, boolean isFinal);
        void onComplete();
        void onError(String message);
    }

    public SessionHandle start(RecognitionCallback callback) {
        RecognitionParam param = RecognitionParam.builder()
                .apiKey(props.getDashscope().getApiKey())
                .model(ASR_MODEL)
                .format("pcm")
                .sampleRate(SAMPLE_RATE)
                .build();
        Recognition recognition = new Recognition();
        recognition.call(param, new ResultCallback<>() {
            @Override
            public void onEvent(RecognitionResult result) {
                if (result == null || result.getSentence() == null) return;
                String text = result.getSentence().getText();
                if (text == null || text.isBlank()) return;
                try {
                    callback.onText(text, result.isSentenceEnd());
                } catch (Exception e) {
                    log.debug("ASR callback.onText 抛出: {}", e.getMessage());
                }
            }

            @Override
            public void onComplete() {
                try { callback.onComplete(); } catch (Exception ignored) {}
            }

            @Override
            public void onError(Exception e) {
                log.warn("ASR onError: {}", e.getMessage());
                try { callback.onError(e.getMessage() == null ? "asr error" : e.getMessage()); }
                catch (Exception ignored) {}
            }
        });
        return new SessionHandle(recognition);
    }

    public static final class SessionHandle {
        private final Recognition recognition;
        SessionHandle(Recognition recognition) { this.recognition = recognition; }
        public void sendFrame(ByteBuffer buf) {
            if (buf == null || !buf.hasRemaining()) return;
            recognition.sendAudioFrame(buf);
        }
        public void stop() {
            try { recognition.stop(); } catch (Exception e) {
                log.debug("ASR stop: {}", e.getMessage());
            }
        }
    }
}
