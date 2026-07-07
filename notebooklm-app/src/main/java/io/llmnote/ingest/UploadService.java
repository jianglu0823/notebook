package io.llmnote.ingest;

import io.llmnote.config.NotebookLmProperties;
import io.llmnote.notebook.Source;
import io.llmnote.notebook.SourceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class UploadService {

    private final NotebookLmProperties props;
    private final SourceRepository sourceRepo;
    private final IngestService ingestService;
    private final AudioTranscribeService audioTranscribeService;
    private final ImageUnderstandingService imageUnderstandingService;

    /** 保存上传文件并创建 Source(状态 PENDING),随后异步摄取。返回 Source。 */
    public Source upload(Long notebookId, Long noteId, MultipartFile file) throws IOException {
        String original = file.getOriginalFilename() == null ? "unnamed" : file.getOriginalFilename();
        String type = detectType(original);

        Path dir = Paths.get(props.getStorage().getUploadDir(), String.valueOf(notebookId), String.valueOf(noteId))
                .toAbsolutePath().normalize();
        Files.createDirectories(dir);
        String stored = UUID.randomUUID() + "_" + original.replaceAll("[/\\\\]", "_");
        Path target = dir.resolve(stored);
        file.transferTo(target.toFile());

        Source source = new Source();
        source.setNotebookId(notebookId);
        source.setNoteId(noteId);
        source.setName(original);
        source.setType(type);
        source.setStoragePath(target.toString());
        source.setStatus("PENDING");
        source = sourceRepo.save(source);

        asyncIngest(source.getId(), target, type);
        return source;
    }

    @Async
    public void asyncIngest(Long sourceId, Path path, String type) {
        Source source = sourceRepo.findById(sourceId).orElse(null);
        if (source == null) return;
        if ("AUDIO".equals(type)) {
            audioTranscribeService.transcribeAndIngest(source, path);
        } else if ("IMAGE".equals(type)) {
            imageUnderstandingService.describeAndIngest(source, path);
        } else {
            ingestService.ingestFile(source, path);
        }
    }

    private String detectType(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".pdf")) return "PDF";
        if (lower.endsWith(".doc") || lower.endsWith(".docx")) return "DOCX";
        if (lower.endsWith(".txt") || lower.endsWith(".md")) return "TEXT";
        if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".webp")) return "IMAGE";
        if (lower.endsWith(".mp3") || lower.endsWith(".wav") || lower.endsWith(".m4a") || lower.endsWith(".flac")) return "AUDIO";
        return "TEXT";
    }
}
