package com.park.energy.repo;

import com.park.energy.domain.BillFragment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BillFragmentRepository extends JpaRepository<BillFragment, String> {
    List<BillFragment> findByBillIdOrderBySegmentStartAsc(String billId);
}
