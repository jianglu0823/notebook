package io.llmnote.world;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 写入员工长期记忆/事件流的小助手,供对话/会议/自主行动共用。 */
@Service
@RequiredArgsConstructor
public class AgentMemoryService {

    private final AgentMemoryRepository repo;

    public AgentMemory record(Long agentId, String kind, String content, int importance, Long relatedAgentId) {
        AgentMemory m = new AgentMemory();
        m.setAgentId(agentId);
        m.setKind(kind);
        m.setContent(content == null ? "" : content);
        m.setImportance(Math.max(1, Math.min(10, importance)));
        m.setRelatedAgentId(relatedAgentId);
        return repo.save(m);
    }

    public AgentMemory record(Long agentId, String kind, String content) {
        return record(agentId, kind, content, 5, null);
    }
}
