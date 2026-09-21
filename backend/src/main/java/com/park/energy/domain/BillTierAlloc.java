package com.park.energy.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;

/** 片段电量落入各月度阶梯的分摊记录。 */
@Entity
@Table(name = "bill_tier_alloc")
public class BillTierAlloc {
    @Id
    private String id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bill_id", nullable = false)
    private Bill bill;
    /** 所属片段（普通外键值） */
    @Column(name = "fragment_id", nullable = false)
    private String fragmentId;
    @Column(name = "tier_index", nullable = false)
    private int tierIndex;
    @Column(nullable = false, precision = 18, scale = 9)
    private BigDecimal kwh;
    @Column(name = "cumulative_before", nullable = false, precision = 18, scale = 9)
    private BigDecimal cumulativeBefore;
    @Column(name = "surcharge_per_kwh", nullable = false, precision = 12, scale = 6)
    private BigDecimal surchargePerKwh;

    protected BillTierAlloc() {}

    public BillTierAlloc(String id, Bill bill, String fragmentId, int tierIndex, BigDecimal kwh,
                         BigDecimal cumulativeBefore, BigDecimal surchargePerKwh) {
        this.id = id;
        this.bill = bill;
        this.fragmentId = fragmentId;
        this.tierIndex = tierIndex;
        this.kwh = kwh;
        this.cumulativeBefore = cumulativeBefore;
        this.surchargePerKwh = surchargePerKwh;
    }

    public String getId() { return id; }
    public Bill getBill() { return bill; }
    public String getFragmentId() { return fragmentId; }
    public int getTierIndex() { return tierIndex; }
    public BigDecimal getKwh() { return kwh; }
    public BigDecimal getCumulativeBefore() { return cumulativeBefore; }
    public BigDecimal getSurchargePerKwh() { return surchargePerKwh; }
}
