package com.park.energy.repo;

import com.park.energy.domain.TariffVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.List;

public interface TariffVersionRepository extends JpaRepository<TariffVersion, String> {

    @Query("""
            select distinct t from TariffVersion t
            left join fetch t.touPeriods
            left join fetch t.tierRules
            where t.effectiveFrom < :end and (t.effectiveTo is null or t.effectiveTo > :start)
            order by t.effectiveFrom
            """)
    List<TariffVersion> findOverlappingWithFetch(@Param("start") Instant start, @Param("end") Instant end);

    @Query("""
            select distinct t from TariffVersion t
            left join fetch t.touPeriods
            left join fetch t.tierRules
            order by t.effectiveFrom
            """)
    List<TariffVersion> findAllWithFetch();

    List<TariffVersion> findAllByOrderByEffectiveFromAsc();
}
