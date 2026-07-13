package io.llmnote.world;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 居民关系(亲密度)服务。互动即 {@link #bump} 增亲密度;达阈值自动推进关系状态。
 * 婚姻/生子的最终定夺放在每日结算({@link WorldSimEngine}),这里只维护亲密度与 friend/close/dating 的自然演进。
 *
 * <p>阈值:20→friend,50→close,90→dating(仅当双方均单身)。married 只能由每日结算的婚礼推进。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RelationshipService {

    static final int FRIEND = 20, CLOSE = 50, DATING = 90;

    private final AgentRelationshipRepository repo;
    private final AgentEmployeeRepository employeeRepo;

    /** 取归一化后的关系行(不存在则新建)。aId 恒小于 bId。 */
    @Transactional
    public AgentRelationship edge(Long x, Long y) {
        long a = Math.min(x, y), b = Math.max(x, y);
        return repo.findPair(a, b).orElseGet(() -> {
            AgentRelationship r = new AgentRelationship();
            r.setAId(a);
            r.setBId(b);
            r.setIntimacy(0);
            r.setStatus("stranger");
            r.setInteractions(0);
            return repo.save(r);
        });
    }

    /**
     * 一次互动:亲密度 +delta,互动次数 +1,并按阈值自然推进 friend/close/dating。
     * married 关系不再降级或推进(交给结算)。
     */
    @Transactional
    public AgentRelationship bump(Long x, Long y, int delta) {
        if (x == null || y == null || x.equals(y)) return null;
        AgentRelationship r = edge(x, y);
        r.setIntimacy(Math.max(0, r.getIntimacy() + delta));
        r.setInteractions(r.getInteractions() + 1);
        if (!"married".equals(r.getStatus())) advance(r);
        return repo.save(r);
    }

    /** 按亲密度阈值推进关系状态(不降级;dating 需双方单身)。 */
    private void advance(AgentRelationship r) {
        int in = r.getIntimacy();
        String cur = r.getStatus();
        if (in >= DATING && bothSingle(r) && rank(cur) < rank("dating")) {
            r.setStatus("dating");
        } else if (in >= CLOSE && rank(cur) < rank("close")) {
            r.setStatus("close");
        } else if (in >= FRIEND && rank(cur) < rank("friend")) {
            r.setStatus("friend");
        }
    }

    private boolean bothSingle(AgentRelationship r) {
        AgentEmployee a = employeeRepo.findById(r.getAId()).orElse(null);
        AgentEmployee b = employeeRepo.findById(r.getBId()).orElse(null);
        return a != null && b != null && a.getSpouseId() == null && b.getSpouseId() == null;
    }

    private static int rank(String s) {
        return switch (s == null ? "" : s) {
            case "friend" -> 1;
            case "close" -> 2;
            case "dating" -> 3;
            case "married" -> 4;
            default -> 0;
        };
    }

    /** 某居民的关系边(按亲密度倒序),供详情页展示亲密好友/配偶。 */
    public List<AgentRelationship> forAgent(Long id) {
        return repo.findForAgent(id);
    }
}
