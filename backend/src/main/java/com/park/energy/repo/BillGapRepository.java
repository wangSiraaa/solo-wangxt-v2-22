package com.park.energy.repo;

import com.park.energy.domain.BillGap;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BillGapRepository extends JpaRepository<BillGap, String> {
    List<BillGap> findByBillId(String billId);
}
