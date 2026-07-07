package io.llmnote.qa;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.llmnote.auth.Principal;
import io.llmnote.notebook.NotebookService;
import io.llmnote.qa.QaService.RagContext;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * RAG 问答接口。SSE 流式返回:先发 citations 事件,再逐段发 delta 事件,最后 done。
 * 完成后把问答与引用写入 qa_history。
 */
@Slf4j
@RestController
@RequestMapping("/api/notebooks/{notebookId}/qa")
@RequiredArgsConstructor
public class QaController {

    private final QaService qaService;
    private final QaHistoryRepository historyRepo;
    private final ObjectMapper objectMapper;
    private final NotebookService notebookService;

    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter ask(@PathVariable Long notebookId, @RequestBody AskReq req, Principal principal) {
        // SSE 回调在异步线程执行,ThreadLocal 不可用:在 servlet 线程上先校验可读(本人或系统笔记本)。
        notebookService.getReadable(notebookId, principal.ownerId());

        String sessionId = (req.getSessionId() == null || req.getSessionId().isBlank())
                ? UUID.randomUUID().toString() : req.getSessionId();
        SseEmitter emitter = new SseEmitter(120_000L);

        RagContext ctx;
        try {
            ctx = qaService.buildContext(notebookId, req.getNoteIds(), req.getQuestion());
            emitter.send(SseEmitter.event().name("session").data(sessionId));
            emitter.send(SseEmitter.event().name("citations")
                    .data(objectMapper.writeValueAsString(ctx.getCitations()), MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            log.error("qa buildContext failed: notebook={}", notebookId, e);
            emitter.completeWithError(e);
            return emitter;
        }

        StringBuilder answer = new StringBuilder();
        AtomicReference<List<Citation>> cites = new AtomicReference<>(ctx.getCitations());
        qaService.streamAnswer(ctx.getMessages()).subscribe(
                delta -> {
                    answer.append(delta);
                    try {
                        emitter.send(SseEmitter.event().name("delta").data(delta));
                    } catch (IOException io) {
                        throw new RuntimeException(io);
                    }
                },
                err -> {
                    log.error("qa stream failed: notebook={}", notebookId, err);
                    emitter.completeWithError(err);
                },
                () -> {
                    persist(notebookId, sessionId, req.getQuestion(), answer.toString(), cites.get());
                    try {
                        emitter.send(SseEmitter.event().name("done").data(""));
                    } catch (IOException ignore) {
                    }
                    emitter.complete();
                });
        return emitter;
    }

    /** 会话历史(按 session)。 */
    @GetMapping("/history")
    public List<QaHistory> history(@PathVariable Long notebookId, @RequestParam String sessionId, Principal principal) {
        notebookService.getReadable(notebookId, principal.ownerId());
        return historyRepo.findBySessionIdOrderByIdAsc(sessionId);
    }

    private void persist(Long notebookId, String sessionId, String question, String answer, List<Citation> cites) {
        try {
            QaHistory h = new QaHistory();
            h.setNotebookId(notebookId);
            h.setSessionId(sessionId);
            h.setQuestion(question);
            h.setAnswer(answer);
            h.setCitations(objectMapper.writeValueAsString(cites));
            historyRepo.save(h);
        } catch (Exception e) {
            log.warn("persist qa_history failed: notebook={}", notebookId, e);
        }
    }

    @Data
    public static class AskReq {
        private String question;
        private String sessionId;
        private List<Long> noteIds;
    }
}
