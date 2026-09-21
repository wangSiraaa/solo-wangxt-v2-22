package com.park.energy.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "bill",
        uniqueConstraints = @UniqueConstraint(columnNames = {"meter_id", "billing_month"}))
public class Bill {
    @Id
    private String id;
    @Column(name = "meter_id", nullable = false)
    private String meterId;
    @Column(name = "billing_month", nullable = false, length = 7)
    private String billingMonth;
    @Column(nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    private BillStatus status;
    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount;
    @Column(name = "total_kwh", nullable = false, precision = 18, scale = 9)
    private BigDecimal totalKwh;
    @Column(name = "raw_total_amount", nullable = false, precision = 20, scale = 12)
    private BigDecimal rawTotalAmount;
    @Column(name = "rounding_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal roundingAmount;
    @Column(name = "basis_hash", nullable = false, length = 64)
    private String basisHash;
    /** 完整计算依据快照（JSON）：费率版本、时段定义、阶梯、日历、参与读数指针与缺口 */
    @Column(name = "basis_snapshot", nullable = false, columnDefinition = "text")
    private String basisSnapshot;
    @Column(name = "confirmed_at", nullable = false)
    private Instant confirmedAt;

    @OneToMany(mappedBy = "bill", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<BillLine> lines = new ArrayList<>();

    @OneToMany(mappedBy = "bill", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<BillFragment> fragments = new ArrayList<>();

    @OneToMany(mappedBy = "bill", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<BillTierAlloc> tierAllocs = new ArrayList<>();

    @OneToMany(mappedBy = "bill", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<BillGap> gaps = new ArrayList<>();

    protected Bill() {}

    public Bill(String id, String meterId, String billingMonth, BillStatus status, BigDecimal totalAmount,
                BigDecimal totalKwh, BigDecimal rawTotalAmount, BigDecimal roundingAmount, String basisHash,
                String basisSnapshot, Instant confirmedAt) {
        this.id = id;
        this.meterId = meterId;
        this.billingMonth = billingMonth;
        this.status = status;
        this.totalAmount = totalAmount;
        this.totalKwh = totalKwh;
        this.rawTotalAmount = rawTotalAmount;
        this.roundingAmount = roundingAmount;
        this.basisHash = basisHash;
        this.basisSnapshot = basisSnapshot;
        this.confirmedAt = confirmedAt;
    }

    public String getId() { return id; }
    public String getMeterId() { return meterId; }
    public String getBillingMonth() { return billingMonth; }
    public BillStatus getStatus() { return status; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public BigDecimal getTotalKwh() { return totalKwh; }
    public BigDecimal getRawTotalAmount() { return rawTotalAmount; }
    public BigDecimal getRoundingAmount() { return roundingAmount; }
    public String getBasisHash() { return basisHash; }
    public String getBasisSnapshot() { return basisSnapshot; }
    public Instant getConfirmedAt() { return confirmedAt; }
    public List<BillLine> getLines() { return lines; }
    public List<BillFragment> getFragments() { return fragments; }
    public List<BillTierAlloc> getTierAllocs() { return tierAllocs; }
    public List<BillGap> getGaps() { return gaps; }
}
