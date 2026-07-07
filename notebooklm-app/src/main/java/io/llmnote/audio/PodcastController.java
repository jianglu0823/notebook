package io.llmnote.audio;

import io.llmnote.auth.Principal;
import io.llmnote.notebook.NotebookService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

@RestController
@RequestMapping("/api/notebooks/{notebookId}/podcasts")
@RequiredArgsConstructor
public class PodcastController {

    private final PodcastService podcastService;
    private final PodcastRepository podcastRepo;
    private final NotebookService notebookService;

    /** 触发生成(异步),返回 PENDING 占位记录。可选 noteIds 限定来源。 */
    @PostMapping
    public Podcast generate(@PathVariable Long notebookId,
                            @RequestBody(required = false) PodcastReq req, Principal principal) {
        notebookService.getOwned(notebookId, principal.ownerId());
        List<Long> noteIds = req == null ? null : req.getNoteIds();
        return podcastService.submit(notebookId, noteIds);
    }

    @GetMapping
    public List<Podcast> list(@PathVariable Long notebookId, Principal principal) {
        notebookService.getOwned(notebookId, principal.ownerId());
        return podcastRepo.findByNotebookIdOrderByIdDesc(notebookId);
    }

    @GetMapping("/{id}")
    public Podcast get(@PathVariable Long notebookId, @PathVariable Long id, Principal principal) {
        notebookService.getOwned(notebookId, principal.ownerId());
        return podcastRepo.findById(id)
                .filter(p -> p.getNotebookId().equals(notebookId))
                .orElseThrow(() -> new IllegalArgumentException("podcast not found: " + id));
    }

    /** 播放/下载合成音频。 */
    @GetMapping("/{id}/audio")
    public ResponseEntity<Resource> audio(@PathVariable Long notebookId, @PathVariable Long id, Principal principal) {
        notebookService.getOwned(notebookId, principal.ownerId());
        Podcast p = podcastRepo.findById(id)
                .filter(x -> x.getNotebookId().equals(notebookId))
                .orElseThrow(() -> new IllegalArgumentException("podcast not found: " + id));
        if (p.getAudioPath() == null || !"DONE".equals(p.getStatus())) {
            return ResponseEntity.notFound().build();
        }
        Path path = Paths.get(p.getAudioPath());
        Resource res = new FileSystemResource(path);
        if (!res.exists()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("audio/mpeg"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"podcast_" + id + ".mp3\"")
                .body(res);
    }

    @Data
    public static class PodcastReq {
        private List<Long> noteIds;
    }
}
