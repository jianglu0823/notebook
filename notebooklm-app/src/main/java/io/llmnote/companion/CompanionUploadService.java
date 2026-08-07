package io.llmnote.companion;

import io.llmnote.companion.config.CompanionProperties;
import io.llmnote.config.NotebookLmProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 伴侣图片上传。
 * - 校验 MIME(仅 image/png|image/jpeg|image/webp|gif)+ 扩展名(防御性)+ 大小(< maxUploadBytes)。
 * - 落盘到 {image-dir}/companion/{userId}/{uuid}.{ext},返回形如 /uploads/companion/{userId}/{file} 的 URL。
 * - 不与笔记/Agent 资源混目录;按 ownerId 隔离(虽然实际可重名,UUID 已防)。
 */
@Slf4j
@Service
public class CompanionUploadService {

    private static final Set<String> ALLOWED_TYPES = Set.of(
            "image/png", "image/jpeg", "image/jpg", "image/webp", "image/gif");
    private static final Set<String> ALLOWED_EXTS = Set.of(
            "png", "jpg", "jpeg", "webp", "gif");

    private final NotebookLmProperties props;
    private final CompanionProperties properties;

    public CompanionUploadService(NotebookLmProperties props, CompanionProperties properties) {
        this.props = props;
        this.properties = properties;
    }

    public Saved save(String userId, MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("file 不能为空");
        }
        String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        if (!ALLOWED_TYPES.contains(contentType)) {
            throw new IllegalArgumentException("不支持的图片类型: " + contentType);
        }
        long max = properties.getMaxUploadBytes();
        if (file.getSize() > max) {
            throw new IllegalArgumentException("图片大小超过限制 " + max + " 字节");
        }
        String original = file.getOriginalFilename() == null ? "img" : file.getOriginalFilename();
        String ext = extOf(original);
        if (ext == null || !ALLOWED_EXTS.contains(ext)) {
            throw new IllegalArgumentException("不支持的图片扩展名: " + ext);
        }
        String safeUser = userId == null ? "anon" : userId.replaceAll("[^a-zA-Z0-9_:-]", "_");
        Path dir = Paths.get(props.getStorage().getImageDir(), "companion", safeUser).toAbsolutePath().normalize();
        Files.createDirectories(dir);
        String stored = UUID.randomUUID() + "." + ext;
        Path target = dir.resolve(stored);
        file.transferTo(target.toFile());
        String url = "/uploads/companion/" + safeUser + "/" + stored;
        return new Saved(url, file.getSize());
    }

    public record Saved(String url, long sizeBytes) {}

    private String extOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return null;
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
