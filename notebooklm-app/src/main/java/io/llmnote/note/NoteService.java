package io.llmnote.note;

import io.llmnote.auth.ForbiddenException;
import io.llmnote.ingest.IngestService;
import io.llmnote.notebook.Chunk;
import io.llmnote.notebook.ChunkRepository;
import io.llmnote.notebook.NotebookService;
import io.llmnote.notebook.Source;
import io.llmnote.notebook.SourceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 笔记(notebook 下一层):含富文本手写正文 + 上传文件。
 * 正文本身建模为一条 type=NOTE_BODY 的 source,复用摄取管线进 RAG;编辑正文即删旧 chunk 后重切块。
 */
@Service
@RequiredArgsConstructor
public class NoteService {

    public static final String NOTE_BODY_TYPE = "NOTE_BODY";

    private final NoteRepository noteRepo;
    private final NotebookService notebookService;
    private final SourceRepository sourceRepo;
    private final ChunkRepository chunkRepo;
    private final IngestService ingestService;

    public List<Note> list(Long notebookId, String ownerId) {
        notebookService.getReadable(notebookId, ownerId);
        return noteRepo.findByNotebookIdOrderByIdDesc(notebookId);
    }

    /** 跨笔记本:列出该 owner 名下所有笔记本的全部笔记,按更新时间倒序。 */
    public List<Note> listAllByOwner(String ownerId) {
        return noteRepo.findAllByOwnerId(ownerId);
    }

    /** 取笔记并校验归属(先校验笔记本归属,再校验笔记属于该笔记本)。用于修改类入口。 */
    public Note getOwnedNote(Long notebookId, Long noteId, String ownerId) {
        notebookService.getOwned(notebookId, ownerId);
        return noteRepo.findByIdAndNotebookId(noteId, notebookId)
                .orElseThrow(() -> new ForbiddenException("note not found: " + noteId));
    }

    /** 取笔记并校验可读(本人笔记本或系统笔记本)。用于只读/问答类入口。 */
    public Note getReadableNote(Long notebookId, Long noteId, String ownerId) {
        notebookService.getReadable(notebookId, ownerId);
        return noteRepo.findByIdAndNotebookId(noteId, notebookId)
                .orElseThrow(() -> new ForbiddenException("note not found: " + noteId));
    }

    @Transactional
    public Note create(Long notebookId, String title, String ownerId) {
        notebookService.getOwned(notebookId, ownerId);
        Note note = new Note();
        note.setNotebookId(notebookId);
        note.setTitle(title == null || title.isBlank() ? "未命名笔记" : title.trim());
        return noteRepo.save(note);
    }

    /** 保存正文:更新标题/HTML;转纯文本后维护该笔记的 NOTE_BODY source 并重切块。 */
    @Transactional
    public Note updateBody(Long notebookId, Long noteId, String title, String html, String ownerId) {
        Note note = getOwnedNote(notebookId, noteId, ownerId);
        if (title != null && !title.isBlank()) {
            note.setTitle(title.trim());
        }
        note.setContent(html);
        note = noteRepo.save(note);

        reingestBody(note, html);
        return note;
    }

    @Transactional
    public void delete(Long notebookId, Long noteId, String ownerId) {
        getOwnedNote(notebookId, noteId, ownerId);
        // 删该笔记下所有 source 的 chunk 行,再删 source、笔记本身。Milvus 向量残留(与删笔记本行为一致)。
        for (Source s : sourceRepo.findByNoteId(noteId)) {
            chunkRepo.deleteBySourceId(s.getId());
        }
        for (Source s : sourceRepo.findByNoteId(noteId)) {
            sourceRepo.delete(s);
        }
        noteRepo.deleteById(noteId);
    }

    /** 维护笔记正文对应的 NOTE_BODY source:删旧 chunk → 重切块;正文为空则清理。 */
    private void reingestBody(Note note, String html) {
        String plain = htmlToPlainText(html);

        List<Source> existing = sourceRepo.findByNoteIdAndType(note.getId(), NOTE_BODY_TYPE);
        Source body = existing.isEmpty() ? null : existing.get(0);

        if (plain.isBlank()) {
            if (body != null) {
                chunkRepo.deleteBySourceId(body.getId());
                sourceRepo.delete(body);
            }
            return;
        }

        if (body == null) {
            body = new Source();
            body.setNotebookId(note.getNotebookId());
            body.setNoteId(note.getId());
            body.setType(NOTE_BODY_TYPE);
        } else {
            chunkRepo.deleteBySourceId(body.getId());
        }
        body.setName(note.getTitle());
        body.setStatus("PENDING");
        body.setChunkCount(0);
        body = sourceRepo.save(body);

        ingestService.ingestText(body, plain);
    }

    /** 富文本 HTML → 纯文本:块级标签换行、去标签、反转义常见实体。嵌入向量对格式不敏感,简单处理即可。 */
    static String htmlToPlainText(String html) {
        if (html == null) return "";
        String t = html.replaceAll("(?i)<\\s*(br|p|div|li|h[1-6]|tr|blockquote)[^>]*>", "\n");
        t = t.replaceAll("<[^>]+>", "");
        t = t.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
        t = t.replaceAll("\n{3,}", "\n\n");
        return t.trim();
    }
}
