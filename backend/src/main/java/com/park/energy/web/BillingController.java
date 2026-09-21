package com.park.energy.web;

import com.park.energy.service.BillingService;
import com.park.energy.web.dto.Dtos.*;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
public class BillingController {

    private final BillingService billingService;

    public BillingController(BillingService billingService) {
        this.billingService = billingService;
    }

    /** 试算（不保存）：返回明细 + 计算依据指纹 + 完整快照 */
    @GetMapping("/billing/preview")
    public BillPreviewDto preview(@RequestParam String meter, @RequestParam String month) {
        return billingService.preview(meter, month);
    }

    /** 确认账单：冻结计算依据并保存；重复确认幂等返回已确认账单 */
    @PostMapping("/billing/confirm")
    public BillPreviewDto confirm(@RequestParam String meter, @RequestParam String month) {
        return billingService.confirm(meter, month);
    }

    @GetMapping("/bills")
    public List<BillSummaryDto> list(@RequestParam(required = false) String meter) {
        return billingService.listBills(meter);
    }

    /** 财务追溯：费用项 -> 电量片段 -> 费率版本 -> 阶梯分摊 -> 舍入差额，全部来自已保存快照 */
    @GetMapping("/bills/{id}/trace")
    public BillPreviewDto trace(@PathVariable String id) {
        return billingService.trace(id);
    }
}
