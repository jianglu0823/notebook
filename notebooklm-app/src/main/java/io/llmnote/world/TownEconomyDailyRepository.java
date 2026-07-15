package io.llmnote.world;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface TownEconomyDailyRepository extends JpaRepository<TownEconomyDaily, LocalDate> {

    /** 最新一日经济快照(前端汇总展示用)。 */
    Optional<TownEconomyDaily> findTopByOrderBySimDateDesc();

    /** 近 N 日快照(时间正序,画曲线用)。 */
    List<TownEconomyDaily> findTop30ByOrderBySimDateDesc();
}
