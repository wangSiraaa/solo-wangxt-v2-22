package com.park.energy.web;

import com.park.energy.repository.MeterRepository;
import com.park.energy.service.BillingService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/meters")
public class MeterController {

    private final MeterRepository meterRepo;
    private final BillingService billingService;

    public MeterController(MeterRepository meterRepo, BillingService billingService) {
        this.meterRepo = meterRepo;
        this.billingService = billingService;
    }

    @GetMapping
    public List<MeterRepository.MeterRow> meters() {
        return meterRepo.findAll();
    }

    public record CurvePoint(Instant ts, BigDecimal readingKwh) {}

    public record CurveResponse(String meterCode, String meterName, Instant monthStart,
                                Instant monthEnd, List<CurvePoint> points,
                                List<BillingService.GapView> gaps, boolean gapPresent,
                                String gapPolicy) {
    }

    /** 表计曲线（读数点 + 缺口标记；缺口绝不当零，直接在曲线上断开）。 */
    @GetMapping("/{id}/curve")
    public CurveResponse curve(@PathVariable long id, @RequestParam String month) {
        MeterRepository.MeterRow meter = meterRepo.findById(id);
        if (meter == null) {
            throw new IllegalArgumentException("表计不存在");
        }
        ZoneId tz = ZoneId.of(meter.tz());
        LocalDate m = LocalDate.parse(month + "-01");
        Instant from = m.atStartOfDay(tz).toInstant();
        Instant to = m.plusMonths(1).atStartOfDay(tz).toInstant();
        BillingService.PreparedInputs prep = billingService.prepareIntervals(meter, from, to);
        List<CurvePoint> points = prep.windowReadings().stream()
                .map(r -> new CurvePoint(r.ts(), r.readingKwh())).toList();
        return new CurveResponse(meter.meterCode(), meter.displayName(), from, to, points,
                prep.gaps(), !prep.gaps().isEmpty(),
                "缺失读数不当零：缺口区间不计费并单独挂起，曲线在此断开");
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, String>> handle(RuntimeException e) {
        HttpStatus status = e instanceof IllegalStateException ? HttpStatus.UNPROCESSABLE_ENTITY
                : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(Map.of("error", e.getMessage()));
    }
}
