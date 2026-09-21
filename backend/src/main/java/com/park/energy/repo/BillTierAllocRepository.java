package com.park.energy.repo;

import com.park.energy.domain.BillTierAlloc;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BillTierAllocRepository extends JpaRepository<BillTierAlloc, String> {
    List<BillTierAlloc> findByBillId(String billId);
}
