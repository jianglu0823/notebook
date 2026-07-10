package io.llmnote.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.llmnote.llm.ChatModelFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 智能体小世界的「话题会议/群聊」入口:校验、建 PENDING 记录,把异步编排交给 {@link MeetingRunner}。
 * 编排本体拆到独立 bean 是为了让 {@code @Async} 生效(同类自调用会失效)。全部按 ownerId 隔离。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MeetingService {

    private final ObjectMapper objectMapper;
    private final ChatModelFactory modelFactory;
    private final AgentMeetingRepository repo;
    private final AgentEmployeeRepository employeeRepo;
    private final MeetingRunner runner;

    public AgentMeeting create(String ownerId, String topic, List<Long> participantIds, Integer maxRounds, String model) {
        if (participantIds == null || participantIds.size() < 2) {
            throw new IllegalArgumentException("至少选择 2 名员工参会");
        }
        List<AgentEmployee> members = loadMembers(participantIds);
        if (members.size() < 2) throw new IllegalArgumentException("有效参会员工不足 2 名");

        AgentMeeting m = new AgentMeeting();
        m.setOwnerId(ownerId);
        m.setTopic(topic == null || topic.isBlank() ? "自由讨论" : topic.trim());
        int rounds = maxRounds == null ? 3 : Math.max(1, Math.min(5, maxRounds));
        m.setMaxRounds(rounds);
        m.setModel(modelFactory.normalize(model));
        m.setStatus("PENDING");
        try {
            m.setParticipantIds(objectMapper.writeValueAsString(
                    members.stream().map(AgentEmployee::getId).toList()));
        } catch (Exception ignore) { /* 序列化失败不阻断 */ }
        m = repo.save(m);
        runner.run(m.getId());
        return m;
    }

    public List<AgentMeeting> list(String ownerId) {
        return repo.findByOwnerIdOrderByIdDesc(ownerId);
    }

    public AgentMeeting get(Long id, String ownerId) {
        return owned(id, ownerId);
    }

    public void delete(Long id, String ownerId) {
        repo.delete(owned(id, ownerId));
    }

    /** 居民全局共享,不再按 owner 过滤,只按 id 取在册居民。 */
    private List<AgentEmployee> loadMembers(List<Long> ids) {
        List<AgentEmployee> out = new ArrayList<>();
        for (Long eid : ids) {
            employeeRepo.findById(eid).ifPresent(out::add);
        }
        return out;
    }

    private AgentMeeting owned(Long id, String ownerId) {
        return repo.findById(id)
                .filter(m -> m.getOwnerId().equals(ownerId))
                .orElseThrow(() -> new IllegalArgumentException("meeting not found: " + id));
    }
}
