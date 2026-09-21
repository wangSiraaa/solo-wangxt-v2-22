package com.park.energy.repo;

import com.park.energy.domain.MeterReading;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface MeterReadingRepository extends JpaRepository<MeterReading, String> {

    @Query("select r from MeterReading r where r.meterId = :meterId and r.ts between :start and :end order by r.ts")
    List<MeterReading> findWindow(@Param("meterId") String meterId,
                                  @Param("start") Instant start, @Param("end") Instant end);

    @Query("select r from MeterReading r where r.meterId = :meterId and r.ts <= :ts order by r.ts desc limit 1")
    Optional<MeterReading> findLastAtOrBefore(@Param("meterId") String meterId, @Param("ts") Instant ts);

    @Query("select r from MeterReading r where r.meterId = :meterId and r.ts >= :ts order by r.ts asc limit 1")
    Optional<MeterReading> findFirstAtOrAfter(@Param("meterId") String meterId, @Param("ts") Instant ts);

    @Query("select count(r) from MeterReading r where r.meterId = :meterId and r.ts = :ts")
    long countAt(@Param("meterId") String meterId, @Param("ts") Instant ts);

    @Query("select min(r.ts) from MeterReading r where r.meterId = :meterId")
    Optional<Instant> findMinTs(@Param("meterId") String meterId);

    @Query("select max(r.ts) from MeterReading r where r.meterId = :meterId")
    Optional<Instant> findMaxTs(@Param("meterId") String meterId);
}
