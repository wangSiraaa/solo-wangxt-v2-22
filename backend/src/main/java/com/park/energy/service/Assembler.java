package com.park.energy.service;

import com.park.energy.billing.BillingModels.*;
import com.park.energy.domain.*;
import com.park.energy.web.dto.Dtos.*;

import java.util.*;

/** 计费结果（试算内存结果 / 已落库账单）-> 对外 DTO 的统一装配。 */
final class Assembler {

    private Assembler() {}

    static BillPreviewDto toPreview(BillingService.Context ctx, Result result, String hash, String snapshot) {
        List<FragmentDto> fragmentDtos = new ArrayList<>();
        List<LineDto> lineDtos = new ArrayList<>();

        // 用 “版本#时段” 聚合片段到能量费用行；片段给临时 id
        Map<String, List<String>> energyFragIds = new LinkedHashMap<>();
        int fseq = 0;
        for (Fragment f : result.fragments()) {
            for (Segment s : f.segments()) {
                String fid = "pfrag-" + fseq++;
                energyFragIds.computeIfAbsent(s.tariffId() + "#" + s.periodType(), k -> new ArrayList<>()).add(fid);
                fragmentDtos.add(new FragmentDto(fid, null,
                        f.intervalStart(), f.intervalEnd(),
                        f.readingStart().toPlainString(), f.readingEnd().toPlainString(), f.intervalKwh().toPlainString(),
                        s.segmentStart(), s.segmentEnd(), s.share().toPlainString(), s.kwh().toPlainString(),
                        s.periodType().name(), s.periodType().getLabel(), s.dayType().name(),
                        s.tariffId(), ctx.tariffCode(s.tariffId()), s.pricePerKwh().toPlainString(),
                        List.of()));
            }
        }

        int order = 1;
        for (EnergyLine l : result.energyLines()) {
            String lid = "pline-" + lineDtos.size();
            lineDtos.add(new LineDto(lid, "ENERGY", l.periodType().name(), l.periodType().getLabel(), null,
                    l.tariffId(), ctx.tariffCode(l.tariffId()), l.kwh().toPlainString(),
                    l.rawAmount().toPlainString(), l.amount().toPlainString(), order++,
                    energyFragIds.getOrDefault(l.tariffId() + "#" + l.periodType().name(), List.of())));
        }
        for (TierLine l : result.tierLines()) {
            lineDtos.add(new LineDto("pline-" + lineDtos.size(), "TIER", null, null, l.tierIndex(),
                    l.tariffId(), ctx.tariffCode(l.tariffId()), l.kwh().toPlainString(),
                    l.rawAmount().toPlainString(), l.amount().toPlainString(), order++, List.of()));
        }
        lineDtos.add(new LineDto("pline-round", "ROUNDING", null, null, null,
                null, null, "0", result.roundingAmount().toPlainString(),
                result.roundingAmount().toPlainString(), order, List.of()));

        List<GapDto> gapDtos = result.gaps().stream()
                .map(g -> new GapDto(g.gapStart(), g.gapEnd(), g.reason(), g.detail())).toList();

        return new BillPreviewDto(null, ctx.meterCode(), ctx.meterName(), ctx.billingMonth(),
                "PREVIEW", null,
                result.totalAmount().toPlainString(), result.totalKwh().toPlainString(),
                result.rawTotal().toPlainString(), result.roundedLineSum().toPlainString(),
                result.roundingAmount().toPlainString(), hash, snapshot,
                lineDtos, fragmentDtos, gapDtos);
    }

    static BillPreviewDto toPersisted(Bill bill, String meterCode, String meterName,
                                      List<BillLine> lines, List<BillFragment> fragments,
                                      List<BillTierAlloc> allocs, List<BillGap> gaps0) {
        Map<String, String> lineTariffCode = new HashMap<>();
        List<LineDto> lineDtos = new ArrayList<>();
        for (BillLine l : lines) {
            String label = l.getPeriodType() == null ? null : l.getPeriodType().getLabel();
            lineTariffCode.put(l.getId(), l.getTariffCode());
            lineDtos.add(new LineDto(l.getId(), l.getLineKind().name(),
                    l.getPeriodType() == null ? null : l.getPeriodType().name(), label,
                    l.getTierIndex(), l.getTariffVersionId(), l.getTariffCode(),
                    l.getKwh().toPlainString(), l.getRawAmount().toPlainString(), l.getAmount().toPlainString(),
                    l.getLineOrder(), new ArrayList<>()));
        }
        Map<String, LineDto> lineById = new LinkedHashMap<>();
        lineDtos.forEach(l -> lineById.put(l.id(), l));

        // 片段 -> 行
        Map<String, List<String>> fragIdsByLine = new HashMap<>();
        Map<String, FragmentDto> fragDtoById = new LinkedHashMap<>();
        for (BillFragment f : fragments) {
            String lineId = f.getBillLineId();
            String label = f.getPeriodType().getLabel();
            FragmentDto dto = new FragmentDto(f.getId(), lineId,
                    f.getIntervalStart(), f.getIntervalEnd(),
                    f.getReadingStart().toPlainString(), f.getReadingEnd().toPlainString(),
                    f.getIntervalKwh().toPlainString(),
                    f.getSegmentStart(), f.getSegmentEnd(), f.getSegmentShare().toPlainString(),
                    f.getKwh().toPlainString(), f.getPeriodType().name(), label, f.getDayType().name(),
                    f.getTariffVersionId(), lineTariffCode.get(lineId), f.getPricePerKwh().toPlainString(),
                    new ArrayList<>());
            fragDtoById.put(f.getId(), dto);
            if (lineId != null) {
                fragIdsByLine.computeIfAbsent(lineId, k -> new ArrayList<>()).add(f.getId());
            }
        }

        for (BillTierAlloc a : allocs) {
            FragmentDto f = fragDtoById.get(a.getFragmentId());
            if (f != null) {
                f.tierAllocs().add(new TierAllocDto(a.getId(), a.getFragmentId(),
                        a.getTierIndex(), a.getKwh().toPlainString(),
                        a.getCumulativeBefore().toPlainString(), a.getSurchargePerKwh().toPlainString()));
            }
        }

        List<LineDto> finalLines = lineDtos.stream().map(l -> {
            List<String> ids = fragIdsByLine.getOrDefault(l.id(), List.of());
            return new LineDto(l.id(), l.kind(), l.periodType(), l.periodLabel(), l.tierIndex(),
                    l.tariffId(), l.tariffCode(), l.kwh(), l.rawAmount(), l.amount(), l.lineOrder(), ids);
        }).toList();

        List<GapDto> gaps = gaps0.stream()
                .map(g -> new GapDto(g.getGapStart(), g.getGapEnd(), g.getReason().name(), g.getDetail()))
                .toList();

        return new BillPreviewDto(bill.getId(), meterCode, meterName, bill.getBillingMonth(),
                bill.getStatus().name(), bill.getConfirmedAt(),
                bill.getTotalAmount().toPlainString(), bill.getTotalKwh().toPlainString(),
                bill.getRawTotalAmount().toPlainString(),
                bill.getTotalAmount().subtract(bill.getRoundingAmount()).toPlainString(),
                bill.getRoundingAmount().toPlainString(), bill.getBasisHash(), bill.getBasisSnapshot(),
                finalLines, new ArrayList<>(fragDtoById.values()), gaps);
    }
}
