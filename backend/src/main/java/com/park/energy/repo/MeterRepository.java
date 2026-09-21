package com.park.energy.repo;

import com.park.energy.domain.Meter;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface MeterRepository extends JpaRepository<Meter, String> {
    Optional<Meter> findByCode(String code);
}
