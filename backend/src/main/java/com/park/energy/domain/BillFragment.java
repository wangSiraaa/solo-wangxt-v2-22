package com.park.energy.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

/** 参与计算的电量片段：一条原始读数区间被“跨午夜/时段切换/费率版本切换/账期边界”切分后的结果。 */
@Entity
@Table(name = "bill_fragment")
public class BillFragment {
    @Id
    private String id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bill_id", nullable = false)
    private Bill bill;
    /** 所属能量费用行（普通外键值，不建 ORM 关联，避免多 bag 插入顺序依赖） */
    @Column(name = "bill_line_id")
    private String billLineId;
    @Column(name = "interval_start", nullable = false)
    private Instant intervalStart;
    @Column(name = "interval_end", nullable = false)
    private Instant intervalEnd;
    @Column(name = "reading_start", nullable = false, precision = 18, scale = 6)
    private BigDecimal readingStart;
    @Column(name = "reading_end", nullable = false, precision = 18, scale = 6)
    private BigDecimal readingEnd;
    @Column(name = "interval_kwh", nullable = false, precision = 18, scale = 9)
    private BigDecimal intervalKwh;
    @Column(name = "segment_start", nullable = false)
    private Instant segmentStart;
    @Column(name = "segment_end", nullable = false)
    private Instant segmentEnd;
    @Column(name = "segment_share", nullable = false, precision = 18, scale = 12)
    private BigDecimal segmentShare;
    @Column(nullable = false, precision = 18, scale = 9)
    private BigDecimal kwh;
    @Column(name = "period_type", nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    private PeriodType periodType;
    @Column(name = "day_type", nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    private DayType dayType;
    @Column(name = "tariff_version_id", nullable = false)
    private String tariffVersionId;
    @Column(name = "price_per_kwh", nullable = false, precision = 12, scale = 6)
    private BigDecimal pricePerKwh;

    protected BillFragment() {}

    public BillFragment(String id, Bill bill, String billLineId, Instant intervalStart, Instant intervalEnd,
                        BigDecimal readingStart, BigDecimal readingEnd, BigDecimal intervalKwh,
                        Instant segmentStart, Instant segmentEnd, BigDecimal segmentShare, BigDecimal kwh,
                        PeriodType periodType, DayType dayType, String tariffVersionId, BigDecimal pricePerKwh) {
        this.id = id;
        this.bill = bill;
        this.billLineId = billLineId;
        this.intervalStart = intervalStart;
        this.intervalEnd = intervalEnd;
        this.readingStart = readingStart;
        this.readingEnd = readingEnd;
        this.intervalKwh = intervalKwh;
        this.segmentStart = segmentStart;
        this.segmentEnd = segmentEnd;
        this.segmentShare = segmentShare;
        this.kwh = kwh;
        this.periodType = periodType;
        this.dayType = dayType;
        this.tariffVersionId = tariffVersionId;
        this.pricePerKwh = pricePerKwh;
    }

    public String getId() { return id; }
    public Bill getBill() { return bill; }
    public String getBillLineId() { return billLineId; }
    public Instant getIntervalStart() { return intervalStart; }
    public Instant getIntervalEnd() { return intervalEnd; }
    public BigDecimal getReadingStart() { return readingStart; }
    public BigDecimal getReadingEnd() { return readingEnd; }
    public BigDecimal getIntervalKwh() { return intervalKwh; }
    public Instant getSegmentStart() { return segmentStart; }
    public Instant getSegmentEnd() { return segmentEnd; }
    public BigDecimal getSegmentShare() { return segmentShare; }
    public BigDecimal getKwh() { return kwh; }
    public PeriodType getPeriodType() { return periodType; }
    public DayType getDayType() { return dayType; }
    public String getTariffVersionId() { return tariffVersionId; }
    public BigDecimal getPricePerKwh() { return pricePerKwh; }
}
