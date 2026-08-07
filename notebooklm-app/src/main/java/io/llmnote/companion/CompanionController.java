package io.llmnote.companion;

import io.llmnote.auth.Principal;
import io.llmnote.companion.dto.CompanionCreateRequest;
import io.llmnote.companion.dto.CompanionDto;
import io.llmnote.companion.dto.CompanionPatchRequest;
import io.llmnote.companion.dto.CompanionTemplateDto;
import io.llmnote.companion.dto.IntimacyDto;
import io.llmnote.companion.dto.MemoryDto;
import io.llmnote.companion.dto.MessageDto;
import io.llmnote.companion.dto.MessagePatchRequest;
import io.llmnote.companion.dto.ModelDto;
import io.llmnote.companion.dto.UploadResponse;
import io.llmnote.companion.enums.MessageAction;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * 伴侣 REST API(CRUD + 模板 + 消息/记忆/亲密度只读 + 模型下拉)。
 * - 路径前缀:/api/companion(由 AuthFilter 放行)。
 * - 主体由 Principal 参数解析(注册用户 / 游客统一用 ownerId 字符串)。
 * - 跨用户访问 → 404(由 ApiExceptionHandler 把 ForbiddenException 映射为 404)。
 */
@RestController
@RequestMapping("/api/companion")
public class CompanionController {

    private final CompanionService companionService;
    private final CompanionMessageService messageService;
    private final CompanionModelService modelService;
    private final CompanionUploadService uploadService;

    public CompanionController(CompanionService companionService,
                               CompanionMessageService messageService,
                               CompanionModelService modelService,
                               CompanionUploadService uploadService) {
        this.companionService = companionService;
        this.messageService = messageService;
        this.modelService = modelService;
        this.uploadService = uploadService;
    }

    // ===== 模型 =====

    @GetMapping("/models")
    public List<ModelDto> listModels() {
        return modelService.listModels();
    }

    // ===== 模板 =====

    @GetMapping("/templates")
    public List<CompanionTemplateDto> listTemplates() {
        return companionService.listTemplates();
    }

    @PostMapping("/templates/{templateKey}")
    public ResponseEntity<CompanionDto> createFromTemplate(@PathVariable String templateKey, Principal principal) {
        CompanionDto created = companionService.createFromTemplate(principal.ownerId(), templateKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    // ===== 伴侣 CRUD =====

    @GetMapping
    public List<CompanionDto> list(Principal principal) {
        return companionService.list(principal.ownerId());
    }

    @PostMapping
    public ResponseEntity<CompanionDto> create(@RequestBody CompanionCreateRequest req, Principal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(companionService.create(principal.ownerId(), req));
    }

    @GetMapping("/{id}")
    public CompanionDto get(@PathVariable Long id, Principal principal) {
        return companionService.get(principal.ownerId(), id);
    }

    @PatchMapping("/{id}")
    public CompanionDto patch(@PathVariable Long id, @RequestBody CompanionPatchRequest req, Principal principal) {
        return companionService.patch(principal.ownerId(), id, req);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id, Principal principal) {
        companionService.softDelete(principal.ownerId(), id);
        return ResponseEntity.noContent().build();
    }

    // ===== 消息 =====

    @GetMapping("/{id}/messages")
    public List<MessageDto> listMessages(@PathVariable Long id,
                                         @RequestParam(required = false) Long beforeId,
                                         @RequestParam(required = false) Integer limit,
                                         Principal principal) {
        return messageService.listMessages(principal.ownerId(), id, beforeId, limit);
    }

    @PatchMapping("/{id}/messages/{messageId}")
    public MessageDto patchMessage(@PathVariable Long id,
                                   @PathVariable Long messageId,
                                   @RequestBody MessagePatchRequest req,
                                   Principal principal) {
        MessageAction action = parseAction(req);
        return messageService.patchMessage(principal.ownerId(), id, messageId, action);
    }

    // ===== 记忆 =====

    @GetMapping("/{id}/memories")
    public List<MemoryDto> listMemories(@PathVariable Long id, Principal principal) {
        return messageService.listMemories(principal.ownerId(), id);
    }

    @DeleteMapping("/{id}/memories/{memoryId}")
    public ResponseEntity<Void> deleteMemory(@PathVariable Long id,
                                             @PathVariable Long memoryId,
                                             Principal principal) {
        messageService.deleteMemory(principal.ownerId(), id, memoryId);
        return ResponseEntity.noContent().build();
    }

    // ===== 亲密度 =====

    @GetMapping("/{id}/intimacy")
    public IntimacyDto getIntimacy(@PathVariable Long id, Principal principal) {
        return toIntimacyDto(messageService.getIntimacy(principal.ownerId(), id));
    }

    // ===== 上传 =====

    @PostMapping(value = "/upload", consumes = "multipart/form-data")
    public UploadResponse upload(@RequestPart("file") MultipartFile file, Principal principal) throws IOException {
        CompanionUploadService.Saved saved = uploadService.save(principal.ownerId(), file);
        return new UploadResponse(saved.url(), saved.sizeBytes());
    }

    // ===== helpers =====

    private MessageAction parseAction(MessagePatchRequest req) {
        if (req == null || req.action() == null || req.action().isBlank()) {
            throw new IllegalArgumentException("action 不能为空(delete|regenerate)");
        }
        try {
            return MessageAction.valueOf(req.action().trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("action 必须是 delete 或 regenerate");
        }
    }

    private IntimacyDto toIntimacyDto(CompanionIntimacy i) {
        return new IntimacyDto(
                i.getCompanionId(),
                i.getScore(),
                i.getDaysCount(),
                i.getLastInteractDate(),
                i.getMilestone7Sent(),
                i.getMilestone30Sent(),
                i.getMilestone100Sent()
        );
    }
}
