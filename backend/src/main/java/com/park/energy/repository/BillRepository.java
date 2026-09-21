package com.park.energy.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Repository
public class BillRepository {

    private final JdbcTemplate jdbc;

    public BillRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record BillRow(long id, long meterId, LocalDate billMonth, String status,
                          BigDecimal totalKwh, BigDecimal totalAmount, BigDecimal roundingDiff,
                          String missingInfo, Instant confirmedAt, Instant createdAt) {}

    public record LineRow(long id, long billId, String lineKind, String label,
                          BigDecimal kwh, BigDecimal unitPrice, BigDecimal amountRaw,
                          BigDecimal amount, int sortNo) {}

    public record FragmentRow(long id, long billLineId, Instant start, Instant end,
                              BigDecimal startReading, BigDecimal endReading, BigDecimal fullKwh,
                              BigDecimal allocatedKwh, BigDecimal allocateRatio,
                              String periodType, Integer tierStepNo,
                              Long rateVersionId, BigDecimal unitPrice) {}

    public List<BillRow> findByMeter(long meterId) {
        return jdbc.query("select * from bill where meter_id = ? order by bill_month desc",
                (rs, n) -> mapBill(rs), meterId);
    }

    public BillRow findById(long id) {
        List<BillRow> rows = jdbc.query("select * from bill where id = ?",
                (rs, n) -> mapBill(rs), id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public BillRow find(long meterId, LocalDate month) {
        List<BillRow> rows = jdbc.query(
                "select * from bill where meter_id = ? and bill_month = ?",
                (rs, n) -> mapBill(rs), meterId, java.sql.Date.valueOf(month));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private BillRow mapBill(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new BillRow(rs.getLong("id"), rs.getLong("meter_id"),
                rs.getDate("bill_month").toLocalDate(), rs.getString("status"),
                rs.getBigDecimal("total_kwh"), rs.getBigDecimal("total_amount"),
                rs.getBigDecimal("rounding_diff"), rs.getString("missing_info"),
                rs.getTimestamp("confirmed_at") == null ? null
                        : rs.getTimestamp("confirmed_at").toInstant(),
                rs.getTimestamp("created_at").toInstant());
    }

    public List<LineRow> findLines(long billId) {
        return jdbc.query("select * from bill_line where bill_id = ? order by sort_no, id",
                (rs, n) -> new LineRow(rs.getLong("id"), rs.getLong("bill_id"),
                        rs.getString("line_kind"), rs.getString("label"),
                        rs.getBigDecimal("kwh"), rs.getBigDecimal("unit_price"),
                        rs.getBigDecimal("amount_raw"), rs.getBigDecimal("amount"),
                        rs.getInt("sort_no")), billId);
    }

    public List<FragmentRow> findFragments(long lineId) {
        return jdbc.query("select * from bill_fragment where bill_line_id = ? order by start_ts, id",
                (rs, n) -> new FragmentRow(rs.getLong("id"), rs.getLong("bill_line_id"),
                        rs.getTimestamp("start_ts").toInstant(), rs.getTimestamp("end_ts").toInstant(),
                        rs.getBigDecimal("start_reading"), rs.getBigDecimal("end_reading"),
                        rs.getBigDecimal("full_kwh"), rs.getBigDecimal("allocated_kwh"),
                        rs.getBigDecimal("allocate_ratio"), rs.getString("period_type"),
                        rs.getObject("tier_step_no", Integer.class),
                        rs.getObject("rate_version_id", Long.class), rs.getBigDecimal("unit_price")),
                lineId);
    }

    /** 写快照（账单 + 行 + 片段）。status 由调用方决定；CONFIRMED 时写 confirmed_at。 */
    @Transactional
    public long saveSnapshot(BillSnapshot snap) {
        List<Long> ids = jdbc.queryForList(
                "select id from bill where meter_id=? and bill_month=?",
                Long.class, snap.meterId(), java.sql.Date.valueOf(snap.billMonth()));
        if (!ids.isEmpty()) {
            jdbc.update("delete from bill where id = ?", ids.get(0));
        }
        long billId = jdbc.queryForObject("""
                insert into bill(meter_id, bill_month, status, total_kwh, total_amount,
                                 rounding_diff, missing_info, confirmed_at)
                values (?,?,?,?,?,?,?,?)
                returning id
                """, Long.class, snap.meterId(), java.sql.Date.valueOf(snap.billMonth()),
                snap.status(), snap.totalKwh(), snap.totalAmount(), snap.roundingDiff(),
                snap.missingInfo(),
                "CONFIRMED".equals(snap.status()) ? Timestamp.from(Instant.now()) : null);

        int sortNo = 0;
        for (LineSnapshot line : snap.lines()) {
            long lineId = jdbc.queryForObject("""
                    insert into bill_line(bill_id, line_kind, label, kwh, unit_price,
                                          amount_raw, amount, sort_no)
                    values (?,?,?,?,?,?,?,?) returning id
                    """, Long.class, billId, line.kind(), line.label(), line.kwh(),
                    line.unitPrice(), line.amountRaw(), line.amount(), sortNo++);
            for (FragmentSnapshot f : line.fragments()) {
                jdbc.update("""
                        insert into bill_fragment(bill_line_id, start_ts, end_ts, start_reading,
                                end_reading, full_kwh, allocated_kwh, allocate_ratio,
                                period_type, tier_step_no, rate_version_id, unit_price)
                        values (?,?,?,?,?,?,?,?,?,?,?,?)
                        """, lineId, Timestamp.from(f.start()), Timestamp.from(f.end()),
                        f.startReading(), f.endReading(), f.fullKwh(), f.allocatedKwh(),
                        f.allocateRatio(), f.periodType(), f.tierStepNo(),
                        f.rateVersionId(), f.unitPrice());
            }
        }
        return billId;
    }

    public void markReversed(long billId) {
        jdbc.update("update bill set status = 'REVERSED' where id = ? and status = 'CONFIRMED'",
                billId);
    }

    public record BillSnapshot(long meterId, LocalDate billMonth, String status,
                               BigDecimal totalKwh, BigDecimal totalAmount,
                               BigDecimal roundingDiff, String missingInfo,
                               List<LineSnapshot> lines) {}

    public record LineSnapshot(String kind, String label, BigDecimal kwh, BigDecimal unitPrice,
                               BigDecimal amountRaw, BigDecimal amount,
                               List<FragmentSnapshot> fragments) {}

    public record FragmentSnapshot(Instant start, Instant end, BigDecimal startReading,
                                   BigDecimal endReading, BigDecimal fullKwh,
                                   BigDecimal allocatedKwh, BigDecimal allocateRatio,
                                   String periodType, Integer tierStepNo,
                                   Long rateVersionId, BigDecimal unitPrice) {}
}
