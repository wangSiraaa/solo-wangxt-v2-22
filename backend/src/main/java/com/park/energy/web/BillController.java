package com.park.energy.web;

import com.park.energy.billing.BillingRuleException;
import com.park.energy.service.BillingService;
import com.park.energy.repository.BillRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/bills")
public class BillController {

    private final BillingService billingService;
    private final BillRepository billRepo;

    public BillController(BillingService billingService, BillRepository billRepo) {
        this.billingService = billingService;
        this.billRepo = billRepo;
    }

    /** 试算（不落库，任何时候可重算）。 */
    @GetMapping("/draft")
    public BillingService.DraftResult draft(@RequestParam long meterId,
                                            @RequestParam String month) {
        return billingService.draft(meterId, parseMonth(month));
    }

    /** 确认 → 保存当时计算依据（快照冻结）。 */
    @PostMapping("/confirm")
    public Map<String, Object> confirm(@RequestParam long meterId,
                                       @RequestParam String month) {
        long id = billingService.confirm(meterId, parseMonth(month));
        return Map.of("billId", id, "status", "CONFIRMED");
    }

    /** 红冲已确认账单（标记 REVERSED，不删快照），之后可以重新试算/确认。 */
    @PostMapping("/{id}/reverse")
    public Map<String, Object> reverse(@PathVariable long id) {
        billingService.reverse(id);
        return Map.of("billId", id, "status", "REVERSED");
    }

    @GetMapping
    public List<BillRepository.BillRow> list(@RequestParam(required = false) Long meterId) {
        if (meterId != null) {
            return billRepo.findByMeter(meterId);
        }
        return List.of();
    }

    @GetMapping("/{id}")
    public BillingService.BillDetail detail(@PathVariable long id) {
        return billingService.loadBill(id);
    }

    /** 财务下钻：费用项 → 参与计算的电量片段（含费率版本 id/单价/分摊比例）。 */
    @GetMapping("/{id}/lines/{lineId}/trace")
    public Map<String, Object> trace(@PathVariable long id, @PathVariable long lineId) {
        BillingService.BillDetail bill = billingService.loadBill(id);
        BillRepository.LineRow line = bill.lines().stream().map(BillingService.BillLineDetail::line)
                .filter(l -> l.id() == lineId).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("费用行不存在: " + lineId));
        return Map.of(
                "billId", id,
                "line", line,
                "fragments", billingService.trace(id, lineId));
    }

    private LocalDate parseMonth(String month) {
        LocalDate d = LocalDate.parse(month + "-01");
        return d.withDayOfMonth(1);
    }

    @ExceptionHandler({BillingRuleException.class, IllegalStateException.class,
            IllegalArgumentException.class})
    public ResponseEntity<Map<String, String>> handleBadRequest(RuntimeException e) {
        HttpStatus status = e instanceof BillingRuleException
                || e instanceof IllegalStateException ? HttpStatus.UNPROCESSABLE_ENTITY
                : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(Map.of("error", e.getMessage()));
    }
}
