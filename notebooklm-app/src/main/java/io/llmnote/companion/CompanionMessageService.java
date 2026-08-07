package io.llmnote.companion;

import io.llmnote.auth.ForbiddenException;
import io.llmnote.companion.dto.MemoryDto;
import io.llmnote.companion.dto.MessageDto;
import io.llmnote.companion.enums.MessageAction;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 伴侣消息/记忆/亲密度的只读与软删服务(B2 阶段)。
 * - 写消息(PATCH regenerate / 新增)由 B3 ChatService 负责,本类只暴露"删"动作。
 * - 跨用户/跨伴侣访问抛 ForbiddenException → 404。
 */
@Service
public class CompanionMessageService {

    private static final int DEFAULT_PAGE = 30;
    private static final int MAX_PAGE = 100;

    private final CompanionRepository companionRepository;
    private final CompanionMessageRepository messageRepository;
    private final CompanionMemoryRepository memoryRepository;
    private final CompanionIntimacyRepository intimacyRepository;
    private final CompanionMemoryService memoryService;
    private final CompanionMapper mapper;

    public CompanionMessageService(CompanionRepository companionRepository,
                                   CompanionMessageRepository messageRepository,
                                   CompanionMemoryRepository memoryRepository,
                                   CompanionIntimacyRepository intimacyRepository,
                                   CompanionMemoryService memoryService,
                                   CompanionMapper mapper) {
        this.companionRepository = companionRepository;
        this.messageRepository = messageRepository;
        this.memoryRepository = memoryRepository;
        this.intimacyRepository = intimacyRepository;
        this.memoryService = memoryService;
        this.mapper = mapper;
    }

    // ===== 消息 =====

    @Transactional(readOnly = true)
    public List<MessageDto> listMessages(String userId, Long companionId, Long beforeId, Integer limit) {
        requireOwned(userId, companionId);
        int size = (limit == null || limit <= 0) ? DEFAULT_PAGE : Math.min(limit, MAX_PAGE);
        return messageRepository
                .findVisible(companionId, beforeId, PageRequest.of(0, size))
                .stream()
                .map(mapper::toMessageDto)
                .toList();
    }

    /**
     * PATCH message 当前只实现 delete,regenerate 走 B3 ChatService(本类先打日志占位,避免路由 405)。
     */
    @Transactional
    public MessageDto patchMessage(String userId, Long companionId, Long messageId, MessageAction action) {
        requireOwned(userId, companionId);
        CompanionMessage m = messageRepository.findByIdAndCompanionId(messageId, companionId)
                .orElseThrow(() -> new ForbiddenException("message not found: " + messageId));
        if (action == null) {
            throw new IllegalArgumentException("action 不能为空(delete|regenerate)");
        }
        switch (action) {
            case DELETE -> m.setHidden(true);
            case REGENERATE -> {
                // 由 B3 实现:删除当前助手消息 → 触发流式重生成 → 新消息入库并返回。
                // B2 阶段先抛 501,前端会用 WS chat.regenerate 通道走 B3。
                throw new UnsupportedOperationException("regenerate 通过 WS chat.regenerate 触发,尚未在 REST 启用");
            }
        }
        return mapper.toMessageDto(m);
    }

    // ===== 记忆 =====

    @Transactional(readOnly = true)
    public List<MemoryDto> listMemories(String userId, Long companionId) {
        requireOwned(userId, companionId);
        return memoryRepository.findByCompanionIdAndDeletedFalseOrderByCreatedAtDesc(companionId)
                .stream()
                .map(this::toMemoryDto)
                .toList();
    }

    @Transactional
    public void deleteMemory(String userId, Long companionId, Long memoryId) {
        requireOwned(userId, companionId);
        int n = memoryRepository.softDelete(memoryId, companionId, userId);
        if (n == 0) {
            throw new ForbiddenException("memory not found: " + memoryId);
        }
        // 同步从 Milvus 删向量(失败不阻断)
        try {
            memoryService.deleteFromMilvus(companionId, memoryId);
        } catch (Exception e) {
            // ignore
        }
    }

    // ===== 亲密度(只读)=====

    @Transactional(readOnly = true)
    public CompanionIntimacy getIntimacy(String userId, Long companionId) {
        requireOwned(userId, companionId);
        return intimacyRepository.findById(companionId)
                .orElseGet(() -> {
                    CompanionIntimacy init = new CompanionIntimacy();
                    init.setCompanionId(companionId);
                    init.setScore(0);
                    init.setDaysCount(0);
                    return init;   // 不入库,首次写入由 B7 recordInteraction 完成
                });
    }

    // ===== 助手 =====

    private Companion requireOwned(String userId, Long id) {
        return companionRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ForbiddenException("companion not found: " + id));
    }

    private MemoryDto toMemoryDto(CompanionMemory m) {
        return new MemoryDto(
                m.getId(),
                m.getCompanionId(),
                m.getContent(),
                m.getSourceMessageId(),
                m.getCreatedAt()
        );
    }
}
