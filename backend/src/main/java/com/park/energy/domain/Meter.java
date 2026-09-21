package com.park.energy.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "meter")
public class Meter {
    @Id
    private String id;
    @Column(nullable = false, unique = true)
    private String code;
    @Column(nullable = false)
    private String name;
    private String zoneId;
    @Column(nullable = false)
    private String timezone;
    @Column(name = "expected_cadence_seconds", nullable = false)
    private int expectedCadenceSeconds;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Meter() {}

    public Meter(String id, String code, String name, String zoneId, String timezone, int expectedCadenceSeconds, Instant createdAt) {
        this.id = id;
        this.code = code;
        this.name = name;
        this.zoneId = zoneId;
        this.timezone = timezone;
        this.expectedCadenceSeconds = expectedCadenceSeconds;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getZoneId() { return zoneId; }
    public String getTimezone() { return timezone; }
    public int getExpectedCadenceSeconds() { return expectedCadenceSeconds; }
    public Instant getCreatedAt() { return createdAt; }
}
