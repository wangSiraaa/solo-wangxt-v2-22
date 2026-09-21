package com.park.energy.repo;

import com.park.energy.domain.Bill;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface BillRepository extends JpaRepository<Bill, String> {

    Optional<Bill> findByMeterIdAndBillingMonth(String meterId, String billingMonth);

    @EntityGraph(attributePaths = {"lines"})
    List<Bill> findByMeterIdOrderByBillingMonthDesc(String meterId);

    @EntityGraph(attributePaths = {"lines"})
    List<Bill> findAllByOrderByBillingMonthAsc();
}
