package com.park.energy.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "bill_line")
public class BillLine {
    @Id
    private String id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bill_id", nullable = false)
    private Bill bill;
    @Column(name = "line_kind", nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    private LineKind lineKind;
    @Column(name = "period_type", length = 16)
    @Enumerated(EnumType.STRING)
    private PeriodType periodType;
    @Column(name = "tier_index")
    private Integer tierIndex;
    @Column(name = "tariff_version_id")
    private String tariffVersionId;
    @Column(name = "tariff_code")
    private String tariffCode;
    @Column(nullable = false, precision = 18, scale = 9)
    private BigDecimal kwh;
    @Column(name = "raw_amount", nullable = false, precision = 20, scale = 12)
    private BigDecimal rawAmount;
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;
    @Column(name = "line_order", nullable = false)
    private int lineOrder;

    protected BillLine() {}

    public BillLine(String id, Bill bill, LineKind lineKind, PeriodType periodType, Integer tierIndex,
                    String tariffVersionId, String tariffCode, BigDecimal kwh, BigDecimal rawAmount,
                    BigDecimal amount, int lineOrder) {
        this.id = id;
        this.bill = bill;
        this.lineKind = lineKind;
        this.periodType = periodType;
        this.tierIndex = tierIndex;
        this.tariffVersionId = tariffVersionId;
        this.tariffCode = tariffCode;
        this.kwh = kwh;
        this.rawAmount = rawAmount;
        this.amount = amount;
        this.lineOrder = lineOrder;
    }

    public String getId() { return id; }
    public Bill getBill() { return bill; }
    public LineKind getLineKind() { return lineKind; }
    public PeriodType getPeriodType() { return periodType; }
    public Integer getTierIndex() { return tierIndex; }
    public String getTariffVersionId() { return tariffVersionId; }
    public String getTariffCode() { return tariffCode; }
    public BigDecimal getKwh() { return kwh; }
    public BigDecimal getRawAmount() { return rawAmount; }
    public BigDecimal getAmount() { return amount; }
    public int getLineOrder() { return lineOrder; }
}
