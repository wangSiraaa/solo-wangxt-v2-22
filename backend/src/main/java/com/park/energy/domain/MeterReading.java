package com.park.energy.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "meter_reading",
        uniqueConstraints = @UniqueConstraint(columnNames = {"meter_id", "ts"}))
public class MeterReading {
    @Id
    private String id;
    @Column(name = "meter_id", nullable = false)
    private String meterId;
    @Column(name = "ts", nullable = false)
    private Instant ts;
    /** 累计表底数（单调不减），十进制定点 */
    @Column(name = "reading_kwh", nullable = false, precision = 18, scale = 6)
    private BigDecimal readingKwh;
    @Column(name = "import_batch_id")
    private String importBatchId;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected MeterReading() {}

    public MeterReading(String id, String meterId, Instant ts, BigDecimal readingKwh, String importBatchId, Instant createdAt) {
        this.id = id;
        this.meterId = meterId;
        this.ts = ts;
        this.readingKwh = readingKwh;
        this.importBatchId = importBatchId;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public String getMeterId() { return meterId; }
    public Instant getTs() { return ts; }
    public BigDecimal getReadingKwh() { return readingKwh; }
    public String getImportBatchId() { return importBatchId; }
    public Instant getCreatedAt() { return createdAt; }
}
