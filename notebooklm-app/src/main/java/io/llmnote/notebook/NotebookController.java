package io.llmnote.notebook;

import io.llmnote.auth.Principal;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/notebooks")
@RequiredArgsConstructor
public class NotebookController {

    private final NotebookService notebookService;

    @GetMapping
    public List<Notebook> list(Principal principal) {
        return notebookService.list(principal.ownerId());
    }

    @GetMapping("/{id}")
    public Notebook get(@PathVariable Long id, Principal principal) {
        return notebookService.getReadable(id, principal.ownerId());
    }

    @PostMapping
    public Notebook create(@RequestBody CreateReq req, Principal principal) {
        return notebookService.create(req.getName(), req.getDescription(), principal.ownerId());
    }

    @PutMapping("/{id}")
    public Notebook update(@PathVariable Long id, @RequestBody UpdateReq req, Principal principal) {
        return notebookService.update(id, req.getName(), req.getDescription(), principal.ownerId());
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id, Principal principal) {
        notebookService.delete(id, principal.ownerId());
    }

    @GetMapping("/{id}/sources")
    public List<Source> sources(@PathVariable Long id, Principal principal) {
        return notebookService.listSources(id, principal.ownerId());
    }

    @Data
    public static class CreateReq {
        @NotBlank
        private String name;
        private String description;
    }

    @Data
    public static class UpdateReq {
        private String name;
        private String description;
    }
}
