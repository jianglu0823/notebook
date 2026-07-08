package io.llmnote.note;

import io.llmnote.auth.Principal;
import io.llmnote.notebook.Source;
import io.llmnote.notebook.SourceRepository;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/notebooks/{notebookId}/notes")
@RequiredArgsConstructor
public class NoteController {

    private final NoteService noteService;
    private final SourceRepository sourceRepo;

    @GetMapping
    public List<Note> list(@PathVariable Long notebookId, Principal principal) {
        return noteService.list(notebookId, principal.ownerId());
    }

    @GetMapping("/{noteId}")
    public Note get(@PathVariable Long notebookId, @PathVariable Long noteId, Principal principal) {
        return noteService.getReadableNote(notebookId, noteId, principal.ownerId());
    }

    @PostMapping
    public Note create(@PathVariable Long notebookId, @RequestBody CreateReq req, Principal principal) {
        return noteService.create(notebookId, req.getTitle(), req.getType(), principal.ownerId());
    }

    /** 保存正文(标题 + 富文本 HTML);后端转纯文本重切块。 */
    @PutMapping("/{noteId}")
    public Note updateBody(@PathVariable Long notebookId, @PathVariable Long noteId,
                           @RequestBody UpdateReq req, Principal principal) {
        return noteService.updateBody(notebookId, noteId, req.getTitle(), req.getContent(), principal.ownerId());
    }

    @DeleteMapping("/{noteId}")
    public void delete(@PathVariable Long notebookId, @PathVariable Long noteId, Principal principal) {
        noteService.delete(notebookId, noteId, principal.ownerId());
    }

    /** 该笔记下的上传文件(不含 NOTE_BODY 正文源)。 */
    @GetMapping("/{noteId}/sources")
    public List<Source> sources(@PathVariable Long notebookId, @PathVariable Long noteId, Principal principal) {
        noteService.getReadableNote(notebookId, noteId, principal.ownerId());
        return sourceRepo.findByNoteId(noteId).stream()
                .filter(s -> !NoteService.NOTE_BODY_TYPE.equals(s.getType()))
                .toList();
    }

    @Data
    public static class CreateReq {
        private String title;
        private String type;
    }

    @Data
    public static class UpdateReq {
        private String title;
        private String content;
    }
}
