package com.park.energy.repo;

import com.park.energy.domain.BillLine;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BillLineRepository extends JpaRepository<BillLine, String> {
    List<BillLine> findByBillIdOrderByLineOrderAsc(String billId);
}
