package io.llmnote.world;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface StockDailyRepository extends JpaRepository<StockDaily, Long> {

    /** 某只股票历史行情(按日期升序,画走势用)。 */
    List<StockDaily> findByCodeOrderBySimDateAsc(String code);

    /** 某日全部股票行情。 */
    List<StockDaily> findBySimDateOrderByCodeAsc(LocalDate simDate);

    /** 幂等 upsert 用:定位某日某股既有记录。 */
    Optional<StockDaily> findBySimDateAndCode(LocalDate simDate, String code);
}
