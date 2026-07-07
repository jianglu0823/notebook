package io.llmnote.qa;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.rag.knowledge.SimpleKnowledge;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.RetrieveConfig;
import io.llmnote.ingest.IngestService;
import io.llmnote.notebook.Chunk;
import io.llmnote.notebook.ChunkRepository;
import io.llmnote.notebook.Source;
import io.llmnote.notebook.SourceRepository;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * RAG 问答:按 notebook 检索相关切块 -> 组装带出处的 prompt -> 流式生成答案。
 * RetrieveConfig 不支持按 payload 过滤,故检索后在应用层按 notebook_id 过滤。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QaService {

    private static final int RETRIEVE_LIMIT = 12;   // 检索后再按 notebook 过滤,故多取一些
    private static final int MAX_CONTEXT = 6;        // 最终喂给模型的切块数
    private static final double SCORE_THRESHOLD = 0.0;

    private final SimpleKnowledge knowledge;
    private final DashScopeChatModel chatModel;
    private final ChunkRepository chunkRepo;
    private final SourceRepository sourceRepo;

    /** 检索并组装上下文(含引用列表与待发送给模型的消息)。noteIds 为空/空列表则回退整本笔记本。 */
    public RagContext buildContext(Long notebookId, List<Long> noteIds, String question) {
        RetrieveConfig cfg = RetrieveConfig.builder()
                .limit(RETRIEVE_LIMIT)
                .scoreThreshold(SCORE_THRESHOLD)
                .build();
        List<Document> docs = knowledge.retrieve(question, cfg).block();
        if (docs == null) docs = List.of();

        boolean byNote = noteIds != null && !noteIds.isEmpty();
        // 检索后在应用层过滤:选中笔记则按 note_id,否则回退整本 notebook_id(payload 回传类型可能是 Number)
        List<Document> filtered = docs.stream()
                .filter(d -> byNote ? matchesNotes(d, noteIds) : matchesNotebook(d, notebookId))
                .limit(MAX_CONTEXT)
                .collect(Collectors.toList());

        // 映射到 MySQL chunk(取出处 + 内容)。以 vectorId=doc.getId() 关联。
        List<String> vectorIds = filtered.stream().map(Document::getId).collect(Collectors.toList());
        Map<String, Chunk> chunkByVid = vectorIds.isEmpty() ? Map.of()
                : chunkRepo.findByVectorIdIn(vectorIds).stream()
                    .collect(Collectors.toMap(Chunk::getVectorId, c -> c, (a, b) -> a));

        List<Citation> citations = new ArrayList<>();
        StringBuilder ctx = new StringBuilder();
        int idx = 1;
        for (Document d : filtered) {
            Chunk c = chunkByVid.get(d.getId());
            String content = c != null ? c.getContent() : d.getMetadata().getContentText();
            if (content == null) content = "";
            Long sourceId = c != null ? c.getSourceId() : null;
            String sourceName = sourceId != null ? sourceNameOf(sourceId) : "未知来源";
            int seq = c != null && c.getSeq() != null ? c.getSeq() : 0;

            citations.add(new Citation(idx, sourceId, sourceName,
                    c != null ? c.getId() : null, seq, snippet(content)));
            ctx.append("[").append(idx).append("] 来源《").append(sourceName).append("》:\n")
               .append(content).append("\n\n");
            idx++;
        }

        List<Msg> messages = new ArrayList<>();
        messages.add(Msg.builder().role(MsgRole.SYSTEM)
                .content(TextBlock.builder().text(systemPrompt()).build()).build());
        String userText = citations.isEmpty()
                ? "资料库中没有检索到相关内容。请如实告知用户无法从资料中找到答案。\n\n问题:" + question
                : "以下是从资料中检索到的相关片段(带编号):\n\n" + ctx
                    + "请依据上述片段回答问题,并在引用处标注对应编号如 [1][2]。\n\n问题:" + question;
        messages.add(Msg.builder().role(MsgRole.USER)
                .content(TextBlock.builder().text(userText).build()).build());

        RagContext rc = new RagContext();
        rc.setCitations(citations);
        rc.setMessages(messages);
        return rc;
    }

    /** 流式生成答案,返回文本增量流(incrementalOutput=true)。 */
    public Flux<String> streamAnswer(List<Msg> messages) {
        return chatModel.stream(messages, List.of(), null)
                .map(this::textOf)
                .filter(s -> !s.isEmpty());
    }

    private boolean matchesNotebook(Document d, Long notebookId) {
        Object v = d.getPayloadValue(IngestService.PAYLOAD_NOTEBOOK_ID);
        if (v instanceof Number n) return n.longValue() == notebookId;
        return v != null && v.toString().equals(String.valueOf(notebookId));
    }

    private boolean matchesNotes(Document d, List<Long> noteIds) {
        Object v = d.getPayloadValue(IngestService.PAYLOAD_NOTE_ID);
        if (v == null) return false;
        long id = (v instanceof Number n) ? n.longValue() : parseLongOr(v.toString(), Long.MIN_VALUE);
        return noteIds.contains(id);
    }

    private static long parseLongOr(String s, long fallback) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return fallback; }
    }

    private String textOf(ChatResponse resp) {
        if (resp.getContent() == null) return "";
        StringBuilder sb = new StringBuilder();
        resp.getContent().forEach(b -> {
            if (b instanceof TextBlock tb && tb.getText() != null) sb.append(tb.getText());
        });
        return sb.toString();
    }

    private String sourceNameOf(Long sourceId) {
        return sourceRepo.findById(sourceId).map(Source::getName).orElse("未知来源");
    }

    private String snippet(String content) {
        String s = content.strip().replaceAll("\\s+", " ");
        return s.length() > 120 ? s.substring(0, 120) + "…" : s;
    }

    private String systemPrompt() {
        return "你是一个严谨的资料问答助手。只能根据用户提供的资料片段作答,"
             + "不得编造资料之外的信息。回答使用简体中文,并在引用具体信息处标注来源编号(如 [1]);"
             + "若资料不足以回答,请明确说明无法从资料中找到答案。";
    }

    @Data
    public static class RagContext {
        private List<Citation> citations;
        private List<Msg> messages;
    }
}
