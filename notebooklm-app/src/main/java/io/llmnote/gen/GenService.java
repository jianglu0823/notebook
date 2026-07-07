package io.llmnote.gen;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.DashScopeChatModel;
import io.llmnote.notebook.Chunk;
import io.llmnote.notebook.ChunkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 文本生成:摘要 / 学习指南 / FAQ。基于 notebook 全量切块拼上下文,调用 qwen 生成结构化文档。
 * 生成为异步任务,状态写入 generated_doc(PENDING -> GENERATING -> DONE/FAILED)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GenService {

    /** 拼接给模型的最大字符数,避免超出上下文窗口。 */
    private static final int MAX_CONTEXT_CHARS = 24_000;

    private final DashScopeChatModel chatModel;
    private final ChunkRepository chunkRepo;
    private final GeneratedDocRepository docRepo;

    /** 创建一个 PENDING 文档并异步生成。noteIds 为空则基于整本笔记本。返回占位记录。 */
    public GeneratedDoc submit(Long notebookId, String kind, List<Long> noteIds) {
        GeneratedDoc doc = new GeneratedDoc();
        doc.setNotebookId(notebookId);
        doc.setKind(kind);
        doc.setStatus("PENDING");
        doc = docRepo.save(doc);
        generateAsync(doc.getId(), noteIds);
        return doc;
    }

    @Async
    public void generateAsync(Long docId, List<Long> noteIds) {
        GeneratedDoc doc = docRepo.findById(docId).orElse(null);
        if (doc == null) return;
        try {
            String context = buildContext(doc.getNotebookId(), noteIds);
            if (context.isBlank()) {
                doc.setStatus("FAILED");
                doc.setContent("该笔记本暂无资料,无法生成。");
                docRepo.save(doc);
                return;
            }
            doc.setStatus("GENERATING");
            docRepo.save(doc);

            String result = complete(promptFor(doc.getKind(), context));
            doc.setContent(result);
            doc.setStatus("DONE");
            docRepo.save(doc);
            log.info("gen done: doc={} kind={} chars={}", docId, doc.getKind(), result.length());
        } catch (Exception e) {
            log.error("gen failed: doc={}", docId, e);
            doc.setStatus("FAILED");
            doc.setContent(e.getMessage());
            docRepo.save(doc);
        }
    }

    private String buildContext(Long notebookId, List<Long> noteIds) {
        List<Chunk> chunks = (noteIds != null && !noteIds.isEmpty())
                ? chunkRepo.findByNoteIdInOrderBySourceIdAscSeqAsc(noteIds)
                : chunkRepo.findByNotebookIdOrderBySourceIdAscSeqAsc(notebookId);
        StringBuilder sb = new StringBuilder();
        for (Chunk c : chunks) {
            if (c.getContent() == null) continue;
            if (sb.length() + c.getContent().length() > MAX_CONTEXT_CHARS) break;
            sb.append(c.getContent()).append("\n\n");
        }
        return sb.toString();
    }

    /** 阻塞式收集流式输出为完整文本(在异步线程中调用)。 */
    private String complete(List<Msg> messages) {
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

    private List<Msg> promptFor(String kind, String context) {
        String system = "你是一个专业的学习资料整理助手。只依据用户提供的资料内容作答,不得编造。输出使用简体中文与 Markdown 格式。";
        String task = switch (kind) {
            case "SUMMARY" -> "请为以下资料撰写一份结构化摘要,包含:核心主题、要点罗列(分条)、关键结论。";
            case "STUDY_GUIDE" -> "请基于以下资料生成一份学习指南,包含:关键概念解释、重点难点、可用于自测的思考题(附参考答案要点)。";
            case "FAQ" -> "请基于以下资料生成一份常见问题解答(FAQ),列出 5-10 个用户可能提出的问题及简明答案。";
            default -> "请基于以下资料生成一份结构化文档。";
        };
        String user = task + "\n\n=== 资料内容 ===\n" + context;
        return List.of(
                Msg.builder().role(MsgRole.SYSTEM).content(TextBlock.builder().text(system).build()).build(),
                Msg.builder().role(MsgRole.USER).content(TextBlock.builder().text(user).build()).build());
    }
}
