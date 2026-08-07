package io.llmnote.companion.ws;

import java.util.List;
import java.util.Map;

/**
 * 服务端 -> 客户端控制帧,kind 决定其余字段。
 *   "ready"        { ownerId }
 *   "chat.delta"   { messageId, delta }
 *   "chat.done"    { messageId, fullText, model, inputTokens, outputTokens, requestId }
 *   "chat.error"   { messageId, error }
 *   "voice.text"   { text, finalFlag }
 *   "tts.start"    { sentenceSeq }
 *   "tts.sentence" { sentenceSeq, mime }
 *   "tts.end"      { sentenceSeq }
 *   "error"        { error }
 *   "pong"         {}
 *   "milestone"    { milestoneKind, text }
 *   "reminder"     { reminderKind, text }  // 合规:2 小时使用提醒 / 初始 AI 身份提示
 */
public record ServerFrame(
        String kind,
        String ownerId,
        Long messageId,
        String delta,
        String fullText,
        String model,
        Long inputTokens,
        Long outputTokens,
        String requestId,
        String error,
        String text,
        Boolean finalFlag,
        Integer sentenceSeq,
        String mime,
        String milestoneKind,
        String reminderKind,
        List<Map<String, Object>> attachments
) {
    public static ServerFrame ready(String ownerId) {
        return new ServerFrame("ready", ownerId, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }
    public static ServerFrame delta(Long messageId, String delta) {
        return new ServerFrame("chat.delta", null, messageId, delta, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }
    public static ServerFrame done(Long messageId, String fullText, String model,
                                   Long inputTokens, Long outputTokens, String requestId) {
        return new ServerFrame("chat.done", null, messageId, null, fullText, model,
                inputTokens, outputTokens, requestId, null, null, null, null, null, null, null, null);
    }
    public static ServerFrame chatError(Long messageId, String error) {
        return new ServerFrame("chat.error", null, messageId, null, null, null, null, null, null, error, null, null, null, null, null, null, null);
    }
    public static ServerFrame error(String error) {
        return new ServerFrame("error", null, null, null, null, null, null, null, null, error, null, null, null, null, null, null, null);
    }
    public static ServerFrame voiceText(String text, boolean isFinal) {
        return new ServerFrame("voice.text", null, null, null, null, null, null, null, null, null, text, isFinal, null, null, null, null, null);
    }
    public static ServerFrame ttsStart(int seq) {
        return new ServerFrame("tts.start", null, null, null, null, null, null, null, null, null, null, null, seq, null, null, null, null);
    }
    public static ServerFrame ttsSentence(int seq, String mime) {
        return new ServerFrame("tts.sentence", null, null, null, null, null, null, null, null, null, null, null, seq, mime, null, null, null);
    }
    public static ServerFrame ttsEnd(int seq) {
        return new ServerFrame("tts.end", null, null, null, null, null, null, null, null, null, null, null, seq, null, null, null, null);
    }
    public static ServerFrame pong() {
        return new ServerFrame("pong", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }
    public static ServerFrame milestone(String kind, String text) {
        return new ServerFrame("milestone", null, null, null, null, null, null, null, null, null, text, null, null, null, kind, null, null);
    }
    public static ServerFrame reminder(String kind, String text) {
        return new ServerFrame("reminder", null, null, null, null, null, null, null, null, null, text, null, null, null, null, kind, null);
    }
}
