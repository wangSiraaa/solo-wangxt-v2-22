package com.park.energy.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

@Entity
@Table(name = "tariff_version")
public class TariffVersion {
    @Id
    private String id;
    @Column(nullable = false, unique = true)
    private String code;
    @Column(name = "effective_from", nullable = false)
    private Instant effectiveFrom;
    /** 半开区间上界；NULL 表示开放至今 */
    @Column(name = "effective_to")
    private Instant effectiveTo;
    private String note;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "tariffVersion", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<TouPeriod> touPeriods = new LinkedHashSet<>();

    @OneToMany(mappedBy = "tariffVersion", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<TierRule> tierRules = new LinkedHashSet<>();

    protected TariffVersion() {}

    public TariffVersion(String id, String code, Instant effectiveFrom, Instant effectiveTo, String note, Instant createdAt) {
        this.id = id;
        this.code = code;
        this.effectiveFrom = effectiveFrom;
        this.effectiveTo = effectiveTo;
        this.note = note;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public String getCode() { return code; }
    public Instant getEffectiveFrom() { return effectiveFrom; }
    public Instant getEffectiveTo() { return effectiveTo; }
    public String getNote() { return note; }
    public Set<TouPeriod> getTouPeriods() { return touPeriods; }
    public Set<TierRule> getTierRules() { return tierRules; }
}
