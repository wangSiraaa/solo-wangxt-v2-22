package com.park.energy.domain;

import jakarta.persistence.*;
import java.time.Instant;

/** 缺失读数缺口登记。缺口电量不计费、绝不当作零，只做透明披露。 */
@Entity
@Table(name = "bill_gap")
public class BillGap {
    @Id
    private String id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bill_id", nullable = false)
    private Bill bill;
    @Column(name = "gap_start", nullable = false)
    private Instant gapStart;
    @Column(name = "gap_end", nullable = false)
    private Instant gapEnd;
    @Column(nullable = false, length = 64)
    @Enumerated(EnumType.STRING)
    private GapReason reason;
    private String detail;

    protected BillGap() {}

    public BillGap(String id, Bill bill, Instant gapStart, Instant gapEnd, GapReason reason, String detail) {
        this.id = id;
        this.bill = bill;
        this.gapStart = gapStart;
        this.gapEnd = gapEnd;
        this.reason = reason;
        this.detail = detail;
    }

    public String getId() { return id; }
    public Bill getBill() { return bill; }
    public BillGap setBill(Bill bill) { this.bill = bill; return this; }
    public Instant getGapStart() { return gapStart; }
    public Instant getGapEnd() { return gapEnd; }
    public GapReason getReason() { return reason; }
    public String getDetail() { return detail; }
}
