package io.llmnote.world;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface PlaceRevenueRepository extends JpaRepository<PlaceRevenue, Long> {

    /** 某日各场所营收(金额降序)。 */
    List<PlaceRevenue> findBySimDateOrderByAmountDesc(LocalDate simDate);

    /** 幂等 upsert 用:定位某日某场所既有记录。 */
    Optional<PlaceRevenue> findBySimDateAndPlaceKey(LocalDate simDate, String placeKey);
}
