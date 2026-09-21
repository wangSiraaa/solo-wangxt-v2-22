package com.park.energy.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;

/** 月度阶梯：同一费率版本内按月累计电量分段，附加在 TOU 能量电费之上。 */
@Entity
@Table(name = "tier_rule",
        uniqueConstraints = @UniqueConstraint(columnNames = {"tariff_version_id", "tier_index"}))
public class TierRule {
    @Id
    private String id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tariff_version_id", nullable = false)
    private TariffVersion tariffVersion;
    @Column(name = "tier_index", nullable = false)
    private int tierIndex;
    /** 半开区间上界（kWh），NULL 表示无上限 */
    @Column(name = "upper_kwh", precision = 18, scale = 6)
    private BigDecimal upperKwh;
    @Column(name = "surcharge_per_kwh", nullable = false, precision = 12, scale = 6)
    private BigDecimal surchargePerKwh;

    protected TierRule() {}

    public TierRule(String id, TariffVersion tariffVersion, int tierIndex, BigDecimal upperKwh, BigDecimal surchargePerKwh) {
        this.id = id;
        this.tariffVersion = tariffVersion;
        this.tierIndex = tierIndex;
        this.upperKwh = upperKwh;
        this.surchargePerKwh = surchargePerKwh;
    }

    public String getId() { return id; }
    public TariffVersion getTariffVersion() { return tariffVersion; }
    public int getTierIndex() { return tierIndex; }
    public BigDecimal getUpperKwh() { return upperKwh; }
    public BigDecimal getSurchargePerKwh() { return surchargePerKwh; }
}
