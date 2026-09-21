package com.park.energy.service;

import com.park.energy.billing.BillingEngine;
import com.park.energy.billing.ChargedFragment;
import com.park.energy.billing.RateVersionInfo;
import com.park.energy.billing.ReadingGap;
import com.park.energy.billing.ReadingInterval;
import com.park.energy.billing.TierScheduleInfo;
import com.park.energy.repository.BillRepository;
import com.park.energy.repository.CatalogRepository;
import com.park.energy.repository.MeterRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * 计费编排：取读数 → 判定缺口（缺读数不计费）→ 交给引擎 → 组装账单/快照。
 */
@Service
public class BillingService {

    private final MeterRepository meterRepo;
    private final CatalogRepository catalogRepo;
    private final BillRepository billRepo;
    private final BillingEngine engine = new BillingEngine();

    public BillingService(MeterRepository meterRepo, CatalogRepository catalogRepo,
                          BillRepository billRepo) {
        this.meterRepo = meterRepo;
        this.catalogRepo = catalogRepo;
        this.billRepo = billRepo;
    }

    public record GapView(Instant start, Instant end, long missingMinutes, String reason) {}

    public record DraftResult(
            String meterCode,
            String meterName,
            LocalDate billMonth,
            String status,                // 若已存在账单则返回其状态
            BigDecimal totalKwh,
            BigDecimal totalAmount,
            BigDecimal rawToBillRounding,
            BigDecimal balancingRounding,
            List<LineView> lines,
            List<GapView> gaps,
            List<RateVersionView> versionsUsed,
            String missingSummary,
            boolean gapPresent
    ) {}

    public record LineView(String kind, String label, BigDecimal kwh, BigDecimal unitPrice,
                           BigDecimal amountRaw, BigDecimal amount,
                           List<FragmentView> fragments) {}

    public record FragmentView(Instant start, Instant end, BigDecimal startReading,
                               BigDecimal endReading, BigDecimal fullKwh, BigDecimal allocatedKwh,
                               BigDecimal allocateRatio, String periodType, Long rateVersionId,
                               BigDecimal unitPrice, Integer tierStepNo, boolean clipped) {}

    public record RateVersionView(long id, String code, int versionNo,
                                  Instant effectiveFrom, Instant effectiveTo) {}

    public record PreparedInputs(List<ReadingInterval> intervals, List<GapView> gaps,
                                 List<MeterRepository.ReadingRow> windowReadings) {}

    public DraftResult draft(long meterId, LocalDate month) {
        MeterRepository.MeterRow meter = mustMeter(meterId);
        ZoneId tz = ZoneId.of(meter.tz());
        Instant monthStart = month.atStartOfDay(tz).toInstant();
        Instant monthEnd = month.plusMonths(1).atStartOfDay(tz).toInstant();

        CatalogRepository.ContractRow contract = catalogRepo.findContract(meterId);
        if (contract == null) {
            throw new IllegalArgumentException("表计 " + meter.meterCode() + " 尚未绑定费率方案");
        }
        List<RateVersionInfo> versions = catalogRepo.findRateVersions(
                contract.rateCode(), monthStart, monthEnd);
        TierScheduleInfo tier = contract.tierCode() == null ? null
                : catalogRepo.findTierForMonth(contract.tierCode(), month);

        PreparedInputs prepared = prepareIntervals(meter, monthStart, monthEnd);
        if (prepared.intervals().isEmpty()) {
            throw new IllegalStateException("账月内没有任何可计费的完整读数区间（缺失读数按缺口处理，不当零）");
        }

        BillingEngine.BillCalc calc = engine.calculate(
                prepared.intervals(), month, tz, versions, tier);

        List<LineView> lines = calc.lines().stream().map(l -> new LineView(
                l.kind(), l.label(), l.kwh(), l.unitPrice(), l.amountRaw(), l.amount(),
                l.fragments().stream().map(this::toFragmentView).toList())).toList();

        List<RateVersionView> usedVersions = versions.stream()
                .filter(v -> v.effectiveFrom().isBefore(monthEnd)
                        && (v.effectiveTo() == null || v.effectiveTo().isAfter(monthStart)))
                .map(v -> new RateVersionView(v.id(), v.code(), v.versionNo(),
                        v.effectiveFrom(), v.effectiveTo()))
                .toList();

        String summary = summarizeGaps(prepared.gaps());
        BillRepository.BillRow existing = billRepo.find(meterId, month);
        return new DraftResult(meter.meterCode(), meter.displayName(), month,
                existing == null ? "UNSAVED" : existing.status(),
                calc.totalKwh(), calc.totalAmount(),
                calc.rawToBillRounding(), calc.balancingRounding(),
                lines, prepared.gaps(), usedVersions, summary, !prepared.gaps().isEmpty());
    }

    /**
     * 把窗口内读数序列化为区间，并标注缺口：
     * - 相邻读数间隔超过阈值（预期间隔 ×1.5，缺配置时用 90 分钟）=> 缺口
     * - 月初前无读数 / 月末后无读数（账月开窗/关窗未覆盖）=> 边界缺口
     * 缺口区间不会进入计费。
     */
    public PreparedInputs prepareIntervals(MeterRepository.MeterRow meter,
                                           Instant monthStart, Instant monthEnd) {
        List<MeterRepository.ReadingRow> around =
                meterRepo.findReadingsAround(meter.id(), monthStart, monthEnd);
        List<GapView> gaps = new ArrayList<>();
        List<ReadingInterval> intervals = new ArrayList<>();

        long threshold = Math.round((meter.expectedIntervalMinutes() == null
                ? 60 : meter.expectedIntervalMinutes()) * 1.5);

        if (around.isEmpty()) {
            gaps.add(new GapView(monthStart, monthEnd,
                    Duration.between(monthStart, monthEnd).toMinutes(), "账月内无任何读数"));
            return new PreparedInputs(List.of(), gaps, List.of());
        }
        // 边界缺口：第一个读数晚于月初
        MeterRepository.ReadingRow first = around.get(0);
        if (first.ts().isAfter(monthStart)) {
            gaps.add(new GapView(monthStart, first.ts(),
                    Duration.between(monthStart, first.ts()).toMinutes(), "月初开窗前无读数"));
        }
        MeterRepository.ReadingRow last = around.get(around.size() - 1);
        if (last.ts().isBefore(monthEnd)) {
            gaps.add(new GapView(last.ts(), monthEnd,
                    Duration.between(last.ts(), monthEnd).toMinutes(), "月末关窗前无读数"));
        }

        for (int i = 1; i < around.size(); i++) {
            MeterRepository.ReadingRow a = around.get(i - 1);
            MeterRepository.ReadingRow b = around.get(i);
            long minutes = Duration.between(a.ts(), b.ts()).toMinutes();
            // 只关心与账月相交的部分
            Instant segStart = a.ts().isBefore(monthStart) ? monthStart : a.ts();
            Instant segEnd = b.ts().isAfter(monthEnd) ? monthEnd : b.ts();
            boolean intersects = segStart.isBefore(segEnd);
            if (b.readingKwh().compareTo(a.readingKwh()) < 0) {
                throw new IllegalStateException("读数倒退：" + a.ts() + "=" + a.readingKwh()
                        + " -> " + b.ts() + "=" + b.readingKwh() + "（请先修正表底）");
            }
            if (minutes > threshold) {
                if (intersects) {
                    gaps.add(new GapView(segStart, segEnd,
                            Duration.between(segStart, segEnd).toMinutes(),
                            "连续缺读数（间隔 " + minutes + " 分钟，阈值 " + threshold + "）"));
                }
                continue; // 缺口：不计费，绝不当零
            }
            if (intersects) {
                intervals.add(new ReadingInterval(a.ts(), b.ts(), a.readingKwh(), b.readingKwh(),
                        b.readingKwh().subtract(a.readingKwh())));
            }
        }
        List<MeterRepository.ReadingRow> inWindow =
                meterRepo.findReadings(meter.id(), monthStart, monthEnd);
        return new PreparedInputs(intervals, gaps, inWindow);
    }

    /** 确认账单：保存当时计算依据（快照）。已确认账单不可重算覆盖。 */
    @Transactional
    public long confirm(long meterId, LocalDate month) {
        BillRepository.BillRow existing = billRepo.find(meterId, month);
        if (existing != null && "CONFIRMED".equals(existing.status())) {
            throw new IllegalStateException("账单已确认并冻结，请先红冲再重开试算");
        }
        DraftResult d = draft(meterId, month);
        List<BillRepository.LineSnapshot> lineSnaps = new ArrayList<>();
        for (LineView l : d.lines()) {
            List<BillRepository.FragmentSnapshot> fs = l.fragments().stream()
                    .map(f -> new BillRepository.FragmentSnapshot(f.start(), f.end(),
                            f.startReading(), f.endReading(), f.fullKwh(), f.allocatedKwh(),
                            f.allocateRatio(), f.periodType(), f.tierStepNo(),
                            f.rateVersionId(), f.unitPrice()))
                    .toList();
            lineSnaps.add(new BillRepository.LineSnapshot(l.kind(), l.label(), l.kwh(),
                    l.unitPrice(), l.amountRaw(), l.amount(), fs));
        }
        BillRepository.BillSnapshot snap = new BillRepository.BillSnapshot(
                meterId, month, "CONFIRMED", d.totalKwh(), d.totalAmount(),
                d.balancingRounding(), d.missingSummary(), lineSnaps);
        return billRepo.saveSnapshot(snap);
    }

    public void reverse(long billId) {
        billRepo.markReversed(billId);
    }

    public record BillDetail(BillRepository.BillRow bill, String meterCode, String meterName,
                             List<BillLineDetail> lines, List<GapView> gaps) {}

    public record BillLineDetail(BillRepository.LineRow line,
                                 List<BillRepository.FragmentRow> fragments) {}

    public BillDetail loadBill(long billId) {
        BillRepository.BillRow bill = billRepo.findById(billId);
        if (bill == null) {
            throw new IllegalArgumentException("账单不存在");
        }
        MeterRepository.MeterRow meter = mustMeter(bill.meterId());
        List<BillLineDetail> lines = new ArrayList<>();
        for (BillRepository.LineRow line : billRepo.findLines(billId)) {
            lines.add(new BillLineDetail(line, billRepo.findFragments(line.id())));
        }
        // 缺口摘要来自账单文本；快照不重建缺口区间（历史可重现以快照为准）
        return new BillDetail(bill, meter.meterCode(), meter.displayName(), lines, List.of());
    }

    public List<BillRepository.FragmentRow> trace(long billId, long lineId) {
        return billRepo.findFragments(lineId);
    }

    private MeterRepository.MeterRow mustMeter(long id) {
        MeterRepository.MeterRow meter = meterRepo.findById(id);
        if (meter == null) {
            throw new IllegalArgumentException("表计不存在: " + id);
        }
        return meter;
    }

    private FragmentView toFragmentView(ChargedFragment f) {
        return new FragmentView(f.start(), f.end(), f.startReading(), f.endReading(),
                f.fullKwh(), f.allocatedKwh(), f.allocateRatio(),
                f.periodType() == null ? null : f.periodType().name(),
                f.rateVersionId(), f.unitPrice(), f.tierStepNo(), f.clipped());
    }

    private String summarizeGaps(List<GapView> gaps) {
        if (gaps.isEmpty()) {
            return null;
        }
        long minutes = gaps.stream().mapToLong(GapView::missingMinutes).sum();
        return gaps.size() + " 个读数缺口，共 " + minutes + " 分钟未计费（缺失读数按零以外处理，单独挂起）";
    }
}
