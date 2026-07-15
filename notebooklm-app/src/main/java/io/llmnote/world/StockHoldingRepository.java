package io.llmnote.world;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StockHoldingRepository extends JpaRepository<StockHolding, Long> {

    /** 某居民全部持仓。 */
    List<StockHolding> findByAgentId(Long agentId);

    /** 某居民某股持仓(买卖时定位)。 */
    Optional<StockHolding> findByAgentIdAndCode(Long agentId, String code);

    /** 某股全部持有者(结算/统计用)。 */
    List<StockHolding> findByCode(String code);
}
