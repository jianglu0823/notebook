package io.llmnote.world;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface WorldDailyReportRepository extends JpaRepository<WorldDailyReport, Long> {

    Optional<WorldDailyReport> findBySimDate(LocalDate simDate);

    List<WorldDailyReport> findTop30ByOrderBySimDateDesc();

    boolean existsBySimDate(LocalDate simDate);
}
