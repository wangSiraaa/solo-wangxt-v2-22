package com.park.energy.repo;

import com.park.energy.domain.ImportBatch;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface ImportBatchRepository extends JpaRepository<ImportBatch, String> {
    Optional<ImportBatch> findByContentSha256(String contentSha256);
}
