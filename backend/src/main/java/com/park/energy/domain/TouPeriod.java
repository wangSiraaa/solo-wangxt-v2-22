package com.park.energy.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;

/**
 * 尖峰平谷时段。以固定时区的本地墙钟分钟表示：
 * start_min < end_min 表示同日时段；start_min > end_min 表示跨午夜时段（如 22:00-06:00）。
 */
@Entity
@Table(name = "tou_period")
public class TouPeriod {
    @Id
    private String id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tariff_version_id", nullable = false)
    private TariffVersion tariffVersion;
    @Column(name = "period_type", nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    private PeriodType periodType;
    @Column(name = "start_min", nullable = false)
    private int startMin;
    @Column(name = "end_min", nullable = false)
    private int endMin;
    @Column(name = "price_per_kwh", nullable = false, precision = 12, scale = 6)
    private BigDecimal pricePerKwh;
    private String note;

    protected TouPeriod() {}

    public TouPeriod(String id, TariffVersion tariffVersion, PeriodType periodType, int startMin, int endMin,
                     BigDecimal pricePerKwh, String note) {
        this.id = id;
        this.tariffVersion = tariffVersion;
        this.periodType = periodType;
        this.startMin = startMin;
        this.endMin = endMin;
        this.pricePerKwh = pricePerKwh;
        this.note = note;
    }

    public String getId() { return id; }
    public TariffVersion getTariffVersion() { return tariffVersion; }
    public PeriodType getPeriodType() { return periodType; }
    public int getStartMin() { return startMin; }
    public int getEndMin() { return endMin; }
    public BigDecimal getPricePerKwh() { return pricePerKwh; }
    public String getNote() { return note; }
}
