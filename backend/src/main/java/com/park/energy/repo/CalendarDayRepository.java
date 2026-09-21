package com.park.energy.repo;

import com.park.energy.domain.CalendarDay;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.Optional;

public interface CalendarDayRepository extends JpaRepository<CalendarDay, String> {
    Optional<CalendarDay> findByLocalDate(LocalDate localDate);
    boolean existsByLocalDate(LocalDate localDate);
}
