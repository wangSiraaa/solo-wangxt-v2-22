package com.park.energy.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Repository
public class MeterRepository {

    public record MeterRow(long id, String meterCode, String displayName, String tz,
                           Integer expectedIntervalMinutes) {}

    public record ReadingRow(Instant ts, BigDecimal readingKwh) {}

    private final JdbcTemplate jdbc;

    public MeterRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<MeterRow> findAll() {
        return jdbc.query("select * from meter order by meter_code",
                (rs, n) -> new MeterRow(rs.getLong("id"), rs.getString("meter_code"),
                        rs.getString("display_name"), rs.getString("park_tz"),
                        rs.getObject("expected_interval_minutes", Integer.class)));
    }

    public MeterRow findByCode(String code) {
        List<MeterRow> rows = jdbc.query("select * from meter where meter_code = ?",
                (rs, n) -> new MeterRow(rs.getLong("id"), rs.getString("meter_code"),
                        rs.getString("display_name"), rs.getString("park_tz"),
                        rs.getObject("expected_interval_minutes", Integer.class)),
                code);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public MeterRow findById(long id) {
        List<MeterRow> rows = jdbc.query("select * from meter where id = ?",
                (rs, n) -> new MeterRow(rs.getLong("id"), rs.getString("meter_code"),
                        rs.getString("display_name"), rs.getString("park_tz"),
                        rs.getObject("expected_interval_minutes", Integer.class)),
                id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public long insertMeter(String code, String name, String tz, Integer intervalMin) {
        return jdbc.queryForObject(
                "insert into meter(meter_code, display_name, park_tz, expected_interval_minutes) " +
                        "values (?,?,?,?) returning id",
                Long.class, code, name, tz, intervalMin);
    }

    /** 区间内读数（含两端），按时间升序。 */
    public List<ReadingRow> findReadings(long meterId, Instant from, Instant to) {
        return jdbc.query(
                "select ts, reading_kwh from meter_reading " +
                        "where meter_id = ? and ts >= ? and ts <= ? order by ts",
                (rs, n) -> new ReadingRow(rs.getTimestamp("ts").toInstant(),
                        rs.getBigDecimal("reading_kwh")),
                meterId, Timestamp.from(from), Timestamp.from(to));
    }

    /** 账月窗口前后各一个读数，用于裁剪跨月区间。 */
    public List<ReadingRow> findReadingsAround(long meterId, Instant from, Instant to) {
        List<ReadingRow> before = jdbc.query(
                "select ts, reading_kwh from meter_reading where meter_id = ? and ts < ? " +
                        "order by ts desc limit 1",
                (rs, n) -> new ReadingRow(rs.getTimestamp("ts").toInstant(),
                        rs.getBigDecimal("reading_kwh")), meterId, Timestamp.from(from));
        List<ReadingRow> after = jdbc.query(
                "select ts, reading_kwh from meter_reading where meter_id = ? and ts > ? " +
                        "order by ts asc limit 1",
                (rs, n) -> new ReadingRow(rs.getTimestamp("ts").toInstant(),
                        rs.getBigDecimal("reading_kwh")), meterId, Timestamp.from(to));
        List<ReadingRow> inner = findReadings(meterId, from, to);
        java.util.List<ReadingRow> all = new java.util.ArrayList<>(before);
        all.addAll(inner);
        all.addAll(after);
        return all.stream().sorted(java.util.Comparator.comparing(ReadingRow::ts)).distinct().toList();
    }

    public record UpsertResult(int inserted, int updated) {}

    /**
     * 幂等 upsert：相同 (meter_id, ts) 已存在则覆盖（值不同算 updated，值相同也算 updated 但幂等）。
     * 批量执行。
     */
    public UpsertResult upsertReadings(long meterId, List<ParsedReading> rows, String sourceFile) {
        int inserted = 0;
        int updated = 0;
        for (ParsedReading r : rows) {
            boolean existed = Boolean.TRUE.equals(jdbc.queryForObject(
                    "select exists(select 1 from meter_reading where meter_id=? and ts=?)",
                    Boolean.class, meterId, Timestamp.from(r.ts())));
            jdbc.update("""
                    insert into meter_reading(meter_id, ts, reading_kwh, source_file)
                    values (?,?,?,?)
                    on conflict (meter_id, ts)
                    do update set reading_kwh = excluded.reading_kwh,
                                  source_file = excluded.source_file
                    """, meterId, Timestamp.from(r.ts()), r.readingKwh(), sourceFile);
            if (existed) {
                updated++;
            } else {
                inserted++;
            }
        }
        return new UpsertResult(inserted, updated);
    }

    public record ParsedReading(Instant ts, BigDecimal readingKwh) {}
}
