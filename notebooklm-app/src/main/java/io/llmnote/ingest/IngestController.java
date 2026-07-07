package io.llmnote.ingest;

import io.llmnote.auth.Principal;
import io.llmnote.note.NoteService;
import io.llmnote.notebook.Source;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/api/notebooks/{notebookId}/notes/{noteId}/sources")
@RequiredArgsConstructor
public class IngestController {

    private final UploadService uploadService;
    private final NoteService noteService;

    /** 上传文件到笔记并异步摄取,立即返回 PENDING 状态的 Source(前端轮询状态)。 */
    @PostMapping
    public Source upload(@PathVariable Long notebookId,
                         @PathVariable Long noteId,
                         @RequestParam("file") MultipartFile file,
                         Principal principal) throws IOException {
        noteService.getOwnedNote(notebookId, noteId, principal.ownerId());
        return uploadService.upload(notebookId, noteId, file);
    }
}
