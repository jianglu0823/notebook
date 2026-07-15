package io.llmnote.world;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface AgentTransactionRepository extends JpaRepository<AgentTransaction, Long> {

    List<AgentTransaction> findTop20ByAgentIdOrderByIdDesc(Long agentId);

    /** 某居民某日全部流水(时间序)。 */
    List<AgentTransaction> findByAgentIdAndSimDateOrderByIdAsc(Long agentId, LocalDate simDate);

    /** 某居民某日净收入(delta 求和;无记录返回 null)。 */
    @Query("select coalesce(sum(t.delta),0) from AgentTransaction t where t.agentId=:agentId and t.simDate=:simDate")
    long sumDeltaByAgentAndDate(@Param("agentId") Long agentId, @Param("simDate") LocalDate simDate);

    /** 某居民按月净收入:返回 [year, month, sum] 行。 */
    @Query("select year(t.simDate), month(t.simDate), coalesce(sum(t.delta),0) " +
            "from AgentTransaction t where t.agentId=:agentId and t.simDate is not null " +
            "group by year(t.simDate), month(t.simDate) " +
            "order by year(t.simDate) desc, month(t.simDate) desc")
    List<Object[]> sumDeltaByAgentGroupByMonth(@Param("agentId") Long agentId);

    /** 全镇某日净收入合计(收入正、支出负)。 */
    @Query("select coalesce(sum(t.delta),0) from AgentTransaction t where t.simDate=:simDate")
    long sumDeltaByDate(@Param("simDate") LocalDate simDate);

    /** 全镇某日总收入(仅正 delta)。 */
    @Query("select coalesce(sum(t.delta),0) from AgentTransaction t where t.simDate=:simDate and t.delta>0")
    long sumIncomeByDate(@Param("simDate") LocalDate simDate);

    /** 全镇某日总支出(仅负 delta,返回负数)。 */
    @Query("select coalesce(sum(t.delta),0) from AgentTransaction t where t.simDate=:simDate and t.delta<0")
    long sumExpenseByDate(@Param("simDate") LocalDate simDate);
}
