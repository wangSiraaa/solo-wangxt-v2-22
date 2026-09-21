package com.park.energy.web.dto;

import java.math.BigDecimal;
import java.time.Instant;

/** 对外 DTO 集合。BigDecimal 全部序列化为字符串，避免前端浮点误差。 */
public interface Dtos {

    record MeterDto(String id, String code, String name, String timezone, int expectedCadenceSeconds) {}

    record ReadingPointDto(Instant ts, String readingKwh) {}

    record CurveGapDto(Instant start, Instant end, String reason, String detail) {}

    record CurveDto(String meterCode, java.util.List<ReadingPointDto> points,
                    java.util.List<CurveGapDto> gaps, Instant firstTs, Instant lastTs) {}

    record TouDto(String periodType, String periodLabel, int startMin, int endMin, String pricePerKwh) {}

    record TierDto(int tierIndex, String lowerKwh, String upperKwh, String surchargePerKwh) {}

    record TariffDto(String id, String code, Instant effectiveFrom, Instant effectiveTo, String note,
                     java.util.List<TouDto> tou, java.util.List<TierDto> tiers) {}

    record CalendarDayDto(String localDate, String dayType) {}

    record ImportResultDto(boolean reused, String batchId, String fileName, String sha256,
                           int rowCount, int importedRows, int duplicateRows, int invalidRows,
                           java.util.List<String> errors) {}

    record TierAllocDto(String id, String fragmentId, int tierIndex, String kwh,
                        String cumulativeBefore, String surchargePerKwh) {}

    record FragmentDto(String id, String lineId,
                       Instant intervalStart, Instant intervalEnd,
                       String readingStart, String readingEnd, String intervalKwh,
                       Instant segmentStart, Instant segmentEnd, String share, String kwh,
                       String periodType, String periodLabel, String dayType,
                       String tariffId, String tariffCode, String pricePerKwh,
                       java.util.List<TierAllocDto> tierAllocs) {}

    record LineDto(String id, String kind, String periodType, String periodLabel, Integer tierIndex,
                   String tariffId, String tariffCode, String kwh, String rawAmount, String amount,
                   int lineOrder, java.util.List<String> fragmentIds) {}

    record GapDto(Instant gapStart, Instant gapEnd, String reason, String detail) {}

    record BillPreviewDto(String billId, String meterCode, String meterName, String billingMonth,
                          String status, Instant confirmedAt,
                          String totalAmount, String totalKwh, String rawTotal,
                          String roundedLineSum, String roundingAmount,
                          String basisHash, String basisSnapshot,
                          java.util.List<LineDto> lines,
                          java.util.List<FragmentDto> fragments,
                          java.util.List<GapDto> gaps) {}

    record BillSummaryDto(String billId, String meterCode, String billingMonth, String status,
                          String totalAmount, String totalKwh, String roundingAmount, Instant confirmedAt) {}
}
