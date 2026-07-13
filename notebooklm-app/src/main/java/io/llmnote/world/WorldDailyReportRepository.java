package io.llmnote.world;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface WorldDailyReportRepository extends JpaRepository<WorldDailyReport, Long> {

    Optional<WorldDailyReport> findBySimDate(LocalDate simDate);

    /** 取给定日期之前最近的一条日报(用于天气逐日演变的上一日状态)。 */
    Optional<WorldDailyReport> findFirstBySimDateLessThanOrderBySimDateDesc(LocalDate simDate);

    List<WorldDailyReport> findTop30ByOrderBySimDateDesc();

    boolean existsBySimDate(LocalDate simDate);
}
