package io.llmnote.gen;

import io.llmnote.auth.Principal;
import io.llmnote.notebook.NotebookService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/notebooks/{notebookId}/docs")
@RequiredArgsConstructor
public class GenController {

    private static final Set<String> KINDS = Set.of("SUMMARY", "STUDY_GUIDE", "FAQ");

    private final GenService genService;
    private final GeneratedDocRepository docRepo;
    private final NotebookService notebookService;

    /** 触发生成(异步),返回 PENDING 占位记录。kind: SUMMARY / STUDY_GUIDE / FAQ;可选 noteIds 限定来源。 */
    @PostMapping("/{kind}")
    public GeneratedDoc generate(@PathVariable Long notebookId, @PathVariable String kind,
                                 @RequestBody(required = false) GenReq req, Principal principal) {
        notebookService.getOwned(notebookId, principal.ownerId());
        String k = kind.toUpperCase();
        if (!KINDS.contains(k)) {
            throw new IllegalArgumentException("unsupported kind: " + kind);
        }
        List<Long> noteIds = req == null ? null : req.getNoteIds();
        return genService.submit(notebookId, k, noteIds);
    }

    /** 列出该 notebook 的所有生成文档(最新在前)。 */
    @GetMapping
    public List<GeneratedDoc> list(@PathVariable Long notebookId, Principal principal) {
        notebookService.getOwned(notebookId, principal.ownerId());
        return docRepo.findByNotebookIdOrderByIdDesc(notebookId);
    }

    /** 查询单个文档(用于前端轮询生成状态)。 */
    @GetMapping("/{id}")
    public GeneratedDoc get(@PathVariable Long notebookId, @PathVariable Long id, Principal principal) {
        notebookService.getOwned(notebookId, principal.ownerId());
        return docRepo.findById(id)
                .filter(d -> d.getNotebookId().equals(notebookId))
                .orElseThrow(() -> new IllegalArgumentException("doc not found: " + id));
    }

    @Data
    public static class GenReq {
        private List<Long> noteIds;
    }
}
