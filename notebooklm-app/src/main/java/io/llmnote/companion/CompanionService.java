package io.llmnote.companion;

import io.llmnote.auth.ForbiddenException;
import io.llmnote.companion.config.CompanionProperties;
import io.llmnote.companion.dto.CompanionCreateRequest;
import io.llmnote.companion.dto.CompanionDto;
import io.llmnote.companion.dto.CompanionPatchRequest;
import io.llmnote.companion.dto.CompanionTemplateDto;
import io.llmnote.llm.ChatModelFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 伴侣 CRUD + 模板服务。
 * - 所有读/写都按 userId 过滤,跨用户访问抛 ForbiddenException → 404。
 * - 删除为软删(status=DELETED),同时联级软删该伴侣的 message(m.hidden=true)与 memory(m.deleted=true)。
 * - defaultModel 落库前过 ChatModelFactory.normalize(),非法名称回退默认。
 */
@Service
public class CompanionService {

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_DELETED = "DELETED";

    private final CompanionRepository companionRepository;
    private final CompanionMessageRepository messageRepository;
    private final CompanionMemoryRepository memoryRepository;
    private final CompanionTemplateRepository templateRepository;
    private final CompanionMapper mapper;
    private final ChatModelFactory modelFactory;
    private final CompanionProperties properties;

    public CompanionService(CompanionRepository companionRepository,
                            CompanionMessageRepository messageRepository,
                            CompanionMemoryRepository memoryRepository,
                            CompanionTemplateRepository templateRepository,
                            CompanionMapper mapper,
                            ChatModelFactory modelFactory,
                            CompanionProperties properties) {
        this.companionRepository = companionRepository;
        this.messageRepository = messageRepository;
        this.memoryRepository = memoryRepository;
        this.templateRepository = templateRepository;
        this.mapper = mapper;
        this.modelFactory = modelFactory;
        this.properties = properties;
    }

    // ===== 列表 / 详情 =====

    @Transactional(readOnly = true)
    public List<CompanionDto> list(String userId) {
        return companionRepository
                .findByUserIdAndStatusOrderByLastMessageAtDescIdDesc(userId, STATUS_ACTIVE)
                .stream()
                .map(mapper::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public CompanionDto get(String userId, Long id) {
        return mapper.toDto(requireOwned(userId, id));
    }

    // ===== 创建 =====

    @Transactional
    public CompanionDto create(String userId, CompanionCreateRequest req) {
        if (req == null || req.name() == null || req.name().isBlank()) {
            throw new IllegalArgumentException("name 不能为空");
        }
        if (req.name().length() > 20) {
            throw new IllegalArgumentException("name 长度不能超过 20");
        }
        String defaultModel = modelFactory.normalize(properties.getDefaultModel());
        Companion entity = mapper.fromCreate(req, userId, defaultModel);
        entity.setDefaultModel(modelFactory.normalize(entity.getDefaultModel()));
        entity.setStatus(STATUS_ACTIVE);
        Companion saved = companionRepository.save(entity);
        return mapper.toDto(saved);
    }

    @Transactional
    public CompanionDto createFromTemplate(String userId, String templateKey) {
        CompanionTemplate template = templateRepository.findById(templateKey)
                .orElseThrow(() -> new ForbiddenException("template not found: " + templateKey));
        Companion entity = mapper.fromTemplate(template, userId);
        entity.setDefaultModel(modelFactory.normalize(entity.getDefaultModel()));
        entity.setStatus(STATUS_ACTIVE);
        Companion saved = companionRepository.save(entity);
        return mapper.toDto(saved);
    }

    // ===== 部分更新 =====

    @Transactional
    public CompanionDto patch(String userId, Long id, CompanionPatchRequest req) {
        Companion c = requireOwned(userId, id);
        if (req != null && req.name() != null) {
            if (req.name().isBlank()) throw new IllegalArgumentException("name 不能为空");
            if (req.name().length() > 20) throw new IllegalArgumentException("name 长度不能超过 20");
        }
        if (req != null && req.defaultModel() != null) {
            req = withNormalizedModel(req);
        }
        mapper.applyPatch(c, req, modelFactory.normalize(properties.getDefaultModel()));
        return mapper.toDto(c);
    }

    private CompanionPatchRequest withNormalizedModel(CompanionPatchRequest req) {
        return new CompanionPatchRequest(
                req.name(), req.gender(), req.age(), req.personalityTags(),
                req.bio(), req.voiceStyle(), req.relationshipType(),
                req.intimacyStart(), req.speakingStyle(), req.backgroundStory(),
                modelFactory.normalize(req.defaultModel())
        );
    }

    // ===== 软删 =====

    @Transactional
    public void softDelete(String userId, Long id) {
        Companion c = requireOwned(userId, id);
        c.setStatus(STATUS_DELETED);
        messageRepository.softDeleteByCompanionId(id);
        memoryRepository.softDeleteByCompanionId(id);
        // Milvus 向量在 B4 补;此处只动 MySQL 关系数据,避免在 B2 卡住 B4 进度。
    }

    // ===== 模板 =====

    @Transactional(readOnly = true)
    public List<CompanionTemplateDto> listTemplates() {
        return templateRepository.findAllByOrderByNameAsc().stream()
                .map(mapper::toTemplateDto)
                .toList();
    }

    // ===== 助手 =====

    private Companion requireOwned(String userId, Long id) {
        return companionRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ForbiddenException("companion not found: " + id));
    }

    /** 聊天结束时刷新 last_message_at(由 MessageService / ChatService 调用)。 */
    @Transactional
    public void touchLastMessage(Long id) {
        companionRepository.findById(id).ifPresent(c -> c.setLastMessageAt(LocalDateTime.now()));
    }
}
