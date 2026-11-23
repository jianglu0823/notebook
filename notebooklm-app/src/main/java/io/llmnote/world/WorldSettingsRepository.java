package io.llmnote.world;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;

public interface WorldSettingsRepository extends JpaRepository<WorldSettings, Long> {

    @Modifying
    @Query("update WorldSettings s set s.simDate = :simDate, s.simMinute = :simMinute, "
            + "s.season = :season, s.weather = :weather, s.temperature = :temperature where s.id = :id")
    int updateClock(@Param("id") Long id,
                    @Param("simDate") LocalDate simDate,
                    @Param("simMinute") Integer simMinute,
                    @Param("season") String season,
                    @Param("weather") String weather,
                    @Param("temperature") Integer temperature);
}
