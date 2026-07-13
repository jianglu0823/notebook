package io.llmnote.world;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface AgentProductRepository extends JpaRepository<AgentProduct, Long> {

    List<AgentProduct> findByAgentIdOrderByIdDesc(Long agentId);

    List<AgentProduct> findBySimDateOrderByIdAsc(LocalDate simDate);

    long countByAgentIdAndOccupation(Long agentId, String occupation);

    /** 作品馆:按类型倒序取全部(image/artwork/video/song/chapter)。 */
    List<AgentProduct> findByKindOrderByIdDesc(String kind);

    List<AgentProduct> findByKindInOrderByIdDesc(List<String> kinds);

    /** 连载书籍:某作者的全部章节,按序正序。 */
    List<AgentProduct> findByAgentIdAndKindOrderBySeqAscIdAsc(Long agentId, String kind);
}
