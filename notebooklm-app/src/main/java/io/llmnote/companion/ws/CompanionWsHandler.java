package io.llmnote.companion.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.llmnote.companion.CompanionAsrService;
import io.llmnote.companion.CompanionChatService;
import io.llmnote.companion.CompanionTtsService;
import io.llmnote.companion.config.CompanionProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 伴侣 WebSocket 中继:JSON 控制帧 + 二进制音频帧(语音消息 B6 接入)。
 * - 文本聊天:chat.text → CompanionChatService.handleUserMessage → 推 chat.delta/chat.done
 * - 心跳:ping → pong
 * - 错误:try/catch 内捕获,推 chat.error / error 帧
 * - 鉴权由 CompanionHandshakeInterceptor 在握手时把 ownerId 注入 attributes。
 */
@Slf4j
@Component
public class CompanionWsHandler extends TextWebSocketHandler {

    private final ObjectMapper objectMapper;
    private final CompanionChatService chatService;
    private final CompanionAsrService asrService;
    private final CompanionTtsService ttsService;
    private final CompanionProperties properties;
    private final Map<String, Long> currentMessageIds = new ConcurrentHashMap<>();
    private final Map<String, AsrSession> asrSessions = new ConcurrentHashMap<>();
    private final Map<String, String> pendingVoiceText = new ConcurrentHashMap<>();

    public CompanionWsHandler(ObjectMapper objectMapper,
                              CompanionChatService chatService,
                              CompanionAsrService asrService,
                              CompanionTtsService ttsService,
                              CompanionProperties properties) {
        this.objectMapper = objectMapper;
        this.chatService = chatService;
        this.asrService = asrService;
        this.ttsService = ttsService;
        this.properties = properties;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws IOException {
        Object ownerId = session.getAttributes().get(CompanionHandshakeInterceptor.OWNER_ID);
        send(session, ServerFrame.ready(ownerId == null ? "?" : ownerId.toString()));
        // B9 合规:建连后立即推一条 AI 身份提示
        sendQuietly(session, ServerFrame.reminder("ai_identity",
                "提示:对方是 AI 角色扮演,不是真人。所有回复均为模型生成。"));
        // B9 合规:连续使用 reminderHours(默认 2h)后,推一次"请注意休息 + AI 身份"提醒
        long hours = Math.max(0, properties.getReminderHours());
        if (hours > 0) {
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "companion-reminder");
                t.setDaemon(true);
                return t;
            }).schedule(() -> {
                if (session.isOpen()) {
                    sendQuietly(session, ServerFrame.reminder("long_use",
                            "您已连续使用 " + hours + " 小时,请注意休息。再次提示:对方是 AI。"));
                }
            }, hours, java.util.concurrent.TimeUnit.HOURS);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        ClientFrame frame;
        try {
            frame = objectMapper.readValue(message.getPayload(), ClientFrame.class);
        } catch (IOException e) {
            log.warn("invalid frame from {}: {}", session.getId(), e.getMessage());
            sendQuietly(session, ServerFrame.error("invalid frame: " + e.getMessage()));
            return;
        }
        if (frame == null || frame.kind() == null) {
            sendQuietly(session, ServerFrame.error("missing kind"));
            return;
        }
        switch (frame.kind()) {
            case "ping" -> sendQuietly(session, ServerFrame.pong());
            case "chat.text", "chat.image" -> handleChatText(session, frame);
            case "voice.start" -> handleVoiceStart(session, frame);
            case "voice.stop" -> handleVoiceStop(session, frame);
            default -> sendQuietly(session, ServerFrame.error("unsupported kind: " + frame.kind()));
        }
    }

    private void handleChatText(WebSocketSession session, ClientFrame frame) {
        String ownerId = (String) session.getAttributes().get(CompanionHandshakeInterceptor.OWNER_ID);
        if (ownerId == null) {
            sendQuietly(session, ServerFrame.error("not authenticated"));
            return;
        }
        if (frame.companionId() == null) {
            sendQuietly(session, ServerFrame.error("missing companionId"));
            return;
        }
        runChatTurn(session, ownerId, frame);
    }

    private void handleVoiceStart(WebSocketSession session, ClientFrame frame) {
        String ownerId = (String) session.getAttributes().get(CompanionHandshakeInterceptor.OWNER_ID);
        if (ownerId == null) {
            sendQuietly(session, ServerFrame.error("not authenticated"));
            return;
        }
        if (frame.companionId() == null) {
            sendQuietly(session, ServerFrame.error("missing companionId"));
            return;
        }
        AsrSession old = asrSessions.remove(session.getId());
        if (old != null) old.handle.stop();

        final boolean wantTts = Boolean.TRUE.equals(frame.voiceReply());
        AsrSession as = new AsrSession();
        as.companionId = frame.companionId();
        as.model = frame.model();
        as.wantTts = wantTts;
        as.ownerId = ownerId;
        final String sid = session.getId();
        as.handle = asrService.start(new CompanionAsrService.RecognitionCallback() {
            @Override public void onText(String text, boolean isFinal) {
                if (isFinal) pendingVoiceText.put(sid, text);
                sendQuietly(session, ServerFrame.voiceText(text, isFinal));
            }
            @Override public void onComplete() { }
            @Override public void onError(String message) {
                sendQuietly(session, ServerFrame.error("asr: " + message));
            }
        });
        asrSessions.put(session.getId(), as);
    }

    private void handleVoiceStop(WebSocketSession session, ClientFrame frame) {
        AsrSession as = asrSessions.remove(session.getId());
        if (as == null) {
            sendQuietly(session, ServerFrame.error("no active voice session"));
            return;
        }
        as.handle.stop();
        String text = pendingVoiceText.remove(session.getId());
        if (text == null || text.isBlank()) {
            sendQuietly(session, ServerFrame.error("no speech recognized"));
            return;
        }
        ClientFrame chatFrame = new ClientFrame(
                "chat.text", as.companionId, text, as.model, null, null, as.wantTts);
        runChatTurn(session, as.ownerId, chatFrame);
    }

    private void runChatTurn(WebSocketSession session, String ownerId, ClientFrame frame) {
        final boolean wantTts = Boolean.TRUE.equals(frame.voiceReply());
        chatService.handleUserMessage(
                ownerId,
                frame.companionId(),
                frame.content(),
                frame.model(),
                frame.attachments(),
                new CompanionChatService.DeltaSink() {
                    @Override
                    public void onDelta(String delta) {
                        Long mid = currentMessageIds.get(session.getId());
                        sendQuietly(session, ServerFrame.delta(mid, delta));
                    }

                    @Override
                    public void onDone(String fullText, String model, Long inTok, Long outTok, String requestId) {
                        Long mid = currentMessageIds.remove(session.getId());
                        sendQuietly(session, ServerFrame.done(mid, fullText, model, inTok, outTok, requestId));
                        if (wantTts && fullText != null && !fullText.isBlank()) {
                            pushTts(session, fullText);
                        }
                    }

                    @Override
                    public void onError(String error) {
                        currentMessageIds.remove(session.getId());
                        sendQuietly(session, ServerFrame.error(error));
                    }

                    @Override
                    public void onMilestones(java.util.List<io.llmnote.companion.dto.Milestone> milestones) {
                        if (milestones == null) return;
                        for (var m : milestones) {
                            sendQuietly(session, ServerFrame.milestone(m.kind(), m.text()));
                        }
                    }
                });
    }

    private void pushTts(WebSocketSession session, String fullText) {
        try {
            var sentences = ttsService.synthesizeAll(fullText, null);
            for (var s : sentences) {
                sendQuietly(session, ServerFrame.ttsStart(s.seq()));
                if (!s.failed() && s.mp3() != null && s.mp3().length > 0) {
                    sendQuietly(session, ServerFrame.ttsSentence(s.seq(), "audio/mpeg"));
                    try {
                        synchronized (session) {
                            session.sendMessage(new BinaryMessage(s.mp3()));
                        }
                    } catch (IOException e) {
                        log.warn("send TTS audio failed: session={} seq={} err={}",
                                session.getId(), s.seq(), e.getMessage());
                    }
                }
                sendQuietly(session, ServerFrame.ttsEnd(s.seq()));
            }
        } catch (Exception e) {
            log.warn("TTS pipeline failed: {}", e.getMessage());
            sendQuietly(session, ServerFrame.error("tts: " + e.getMessage()));
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        AsrSession as = asrSessions.get(session.getId());
        if (as == null) {
            sendQuietly(session, ServerFrame.error("voice frame 收到但未起 voice.start"));
            return;
        }
        as.handle.sendFrame(message.getPayload());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        currentMessageIds.remove(session.getId());
        AsrSession as = asrSessions.remove(session.getId());
        if (as != null) as.handle.stop();
        pendingVoiceText.remove(session.getId());
    }

    private void send(WebSocketSession session, ServerFrame frame) throws IOException {
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(frame)));
    }

    private void sendQuietly(WebSocketSession session, ServerFrame frame) {
        try {
            send(session, frame);
        } catch (IOException e) {
            log.warn("send failed: session={} kind={} err={}", session.getId(), frame.kind(), e.getMessage());
        }
    }

    private static final class AsrSession {
        Long companionId;
        String model;
        boolean wantTts;
        String ownerId;
        CompanionAsrService.SessionHandle handle;
    }
}
