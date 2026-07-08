package io.llmnote.note;

import io.llmnote.auth.Principal;
import io.llmnote.notebook.Notebook;
import io.llmnote.notebook.NotebookRepository;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 跨笔记本的全部笔记视图:中栏卡片流用。返回带笔记本名与正文预览的精简 DTO。 */
@RestController
@RequestMapping("/api/notes")
@RequiredArgsConstructor
public class AllNoteController {

    private static final int PREVIEW_LEN = 120;

    private final NoteService noteService;
    private final NotebookRepository notebookRepo;

    @GetMapping
    public List<NoteCard> all(Principal principal) {
        String ownerId = principal.ownerId();
        Map<Long, String> nbNames = notebookRepo.findByOwnerIdOrderByIdDesc(ownerId).stream()
                .collect(Collectors.toMap(Notebook::getId, Notebook::getName, (a, b) -> a));
        return noteService.listAllByOwner(ownerId).stream()
                .map(n -> toCard(n, nbNames.get(n.getNotebookId())))
                .toList();
    }

    private NoteCard toCard(Note n, String notebookName) {
        NoteCard c = new NoteCard();
        c.setId(n.getId());
        c.setNotebookId(n.getNotebookId());
        c.setNotebookName(notebookName);
        c.setTitle(n.getTitle());
        c.setType(n.getType());
        c.setPreview(preview(n.getContent()));
        c.setCreatedAt(n.getCreatedAt());
        c.setUpdatedAt(n.getUpdatedAt());
        return c;
    }

    private String preview(String html) {
        String plain = NoteService.htmlToPlainText(html).replaceAll("\\s+", " ").trim();
        return plain.length() <= PREVIEW_LEN ? plain : plain.substring(0, PREVIEW_LEN) + "…";
    }

    @Data
    public static class NoteCard {
        private Long id;
        private Long notebookId;
        private String notebookName;
        private String title;
        private String type;
        private String preview;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
    }
}
