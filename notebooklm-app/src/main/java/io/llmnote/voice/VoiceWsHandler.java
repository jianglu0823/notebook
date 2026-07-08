package io.llmnote.voice;

import com.alibaba.dashscope.audio.asr.recognition.Recognition;
import com.alibaba.dashscope.audio.asr.recognition.RecognitionParam;
import com.alibaba.dashscope.audio.asr.recognition.RecognitionResult;
import com.alibaba.dashscope.common.ResultCallback;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.llmnote.config.NotebookLmProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 语音转写 WebSocket 中继:
 * - 建连:为该 session 起一个 DashScope 流式识别(paraformer-realtime-v2,pcm/16k),识别结果经 TextMessage 回推(区分中间/最终句)。
 * - 二进制帧:浏览器 PCM16 音频 → recognition.sendAudioFrame。
 * - 关闭:recognition.stop()。
 * 只回传文本,不落库;笔记保存仍由前端走已鉴权的 REST PUT。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VoiceWsHandler extends BinaryWebSocketHandler {

    private static final String ASR_MODEL = "paraformer-realtime-v2";
    private static final int SAMPLE_RATE = 16000;

    private final NotebookLmProperties props;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, Recognition> sessions = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
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
                sendResult(session, text, result.isSentenceEnd());
            }

            @Override
            public void onComplete() {
                sendControl(session, "complete");
            }

            @Override
            public void onError(Exception e) {
                log.warn("ASR error: session={} msg={}", session.getId(), e.getMessage());
                sendControl(session, "error");
            }
        });
        sessions.put(session.getId(), recognition);
        sendControl(session, "ready");
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        Recognition recognition = sessions.get(session.getId());
        if (recognition == null) return;
        try {
            ByteBuffer buf = message.getPayload();
            if (buf.hasRemaining()) recognition.sendAudioFrame(buf);
        } catch (Exception e) {
            log.warn("sendAudioFrame failed: session={} msg={}", session.getId(), e.getMessage());
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // 前端可发 {"type":"stop"} 主动停止;实际停止统一在关闭时处理。
        if (message.getPayload() != null && message.getPayload().contains("stop")) {
            stop(session);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        stop(session);
    }

    private void stop(WebSocketSession session) {
        Recognition recognition = sessions.remove(session.getId());
        if (recognition == null) return;
        try {
            recognition.stop();
        } catch (Exception e) {
            log.debug("recognition stop: session={} msg={}", session.getId(), e.getMessage());
        }
    }

    private void sendResult(WebSocketSession session, String text, boolean sentenceEnd) {
        try {
            synchronized (session) {
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(
                        Map.of("type", "text", "text", text, "end", sentenceEnd))));
            }
        } catch (Exception e) {
            log.debug("send result failed: session={} msg={}", session.getId(), e.getMessage());
        }
    }

    private void sendControl(WebSocketSession session, String status) {
        try {
            if (!session.isOpen()) return;
            synchronized (session) {
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(
                        Map.of("type", "status", "status", status))));
            }
        } catch (Exception e) {
            log.debug("send control failed: session={} msg={}", session.getId(), e.getMessage());
        }
    }
}
