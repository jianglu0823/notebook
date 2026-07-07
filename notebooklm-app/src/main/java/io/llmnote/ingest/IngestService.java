package io.llmnote.ingest;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.knowledge.SimpleKnowledge;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import io.agentscope.core.rag.reader.ReaderInput;
import io.agentscope.core.rag.reader.TikaReader;
import io.llmnote.notebook.Chunk;
import io.llmnote.notebook.ChunkRepository;
import io.llmnote.notebook.Source;
import io.llmnote.notebook.SourceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 摄取管线:文件 -> TikaReader 解析并分块 -> 每块携带出处 payload -> 向量化入 Milvus
 * 同时把切块原文与 Milvus 主键映射写入 MySQL(用于引用展示与 notebook 隔离)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IngestService {

    public static final String PAYLOAD_NOTEBOOK_ID = "notebook_id";
    public static final String PAYLOAD_NOTE_ID = "note_id";
    public static final String PAYLOAD_SOURCE_ID = "source_id";
    public static final String PAYLOAD_SEQ = "seq";

    private final SimpleKnowledge knowledge;
    private final SourceRepository sourceRepo;
    private final ChunkRepository chunkRepo;

    /** 解析并入库一个已落盘的文件源。阻塞式(在异步线程中调用)。 */
    @Transactional
    public void ingestFile(Source source, Path filePath) {
        try {
            source.setStatus("PARSING");
            sourceRepo.save(source);

            // 注意:TikaReader 期望 ReaderInput 内容是文件路径,须用 fromPath(而非 fromFile,后者会把文件内容读成字符串)
            ReaderInput input = ReaderInput.fromPath(filePath);
            List<Document> chunks = new TikaReader().read(input).block();
            ingestChunks(source, chunks);
        } catch (Exception e) {
            log.error("ingest failed: source={}", source.getId(), e);
            markFailed(source, e.getMessage());
        }
    }

    /** 从纯文本入库(供音频转写、网页正文等复用)。先落盘临时文件再走 TikaReader 路径解析。 */
    @Transactional
    public void ingestText(Source source, String text) {
        Path tmp = null;
        try {
            source.setStatus("PARSING");
            sourceRepo.save(source);

            tmp = java.nio.file.Files.createTempFile("nblm-text-", ".txt");
            java.nio.file.Files.writeString(tmp, text);
            List<Document> chunks = new TikaReader().read(ReaderInput.fromPath(tmp)).block();
            ingestChunks(source, chunks);
        } catch (Exception e) {
            log.error("ingest text failed: source={}", source.getId(), e);
            markFailed(source, e.getMessage());
        } finally {
            if (tmp != null) {
                try { java.nio.file.Files.deleteIfExists(tmp); } catch (Exception ignore) {}
            }
        }
    }

    private void ingestChunks(Source source, List<Document> rawChunks) {
        if (rawChunks == null || rawChunks.isEmpty()) {
            markFailed(source, "解析结果为空");
            return;
        }

        // payload 不可变,重建每个 Document 的 metadata,带上出处(供检索后按 notebook 过滤 + 溯源)
        List<Document> chunks = new ArrayList<>(rawChunks.size());
        int seq = 0;
        int totalChars = 0;
        for (Document doc : rawChunks) {
            DocumentMetadata md = doc.getMetadata();
            var mdb = DocumentMetadata.builder()
                    .content(md.getContent())
                    .docId(md.getDocId())
                    .chunkId(md.getChunkId())
                    .addPayload(PAYLOAD_NOTEBOOK_ID, source.getNotebookId())
                    .addPayload(PAYLOAD_SOURCE_ID, source.getId())
                    .addPayload(PAYLOAD_SEQ, seq);
            if (source.getNoteId() != null) {
                mdb.addPayload(PAYLOAD_NOTE_ID, source.getNoteId());
            }
            chunks.add(new Document(mdb.build()));
            totalChars += textOf(doc).length();
            seq++;
        }

        source.setStatus("EMBEDDING");
        sourceRepo.save(source);

        // 向量化并写入 Milvus
        knowledge.addDocuments(chunks).block();

        // 记录切块与 Milvus 主键映射
        List<Chunk> rows = new ArrayList<>();
        int i = 0;
        for (Document doc : chunks) {
            Chunk c = new Chunk();
            c.setSourceId(source.getId());
            c.setNotebookId(source.getNotebookId());
            c.setNoteId(source.getNoteId());
            c.setSeq(i);
            c.setVectorId(doc.getId());
            c.setContent(textOf(doc));
            c.setLocator("seq=" + i);
            rows.add(c);
            i++;
        }
        chunkRepo.saveAll(rows);

        source.setStatus("DONE");
        source.setChunkCount(chunks.size());
        source.setCharCount(totalChars);
        sourceRepo.save(source);
        log.info("ingest done: source={} chunks={}", source.getId(), chunks.size());
    }

    private void markFailed(Source source, String msg) {
        source.setStatus("FAILED");
        source.setErrorMsg(msg != null && msg.length() > 2000 ? msg.substring(0, 2000) : msg);
        sourceRepo.save(source);
    }

    private String textOf(Document doc) {
        var content = doc.getMetadata().getContent();
        if (content instanceof TextBlock tb) {
            return tb.getText() == null ? "" : tb.getText();
        }
        String t = doc.getMetadata().getContentText();
        return t == null ? "" : t;
    }
}
