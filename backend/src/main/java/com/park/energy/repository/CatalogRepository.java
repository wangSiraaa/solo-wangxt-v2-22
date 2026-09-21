package com.park.energy.repository;

import com.park.energy.billing.RateVersionInfo;
import com.park.energy.billing.TierScheduleInfo;
import com.park.energy.billing.TouRule;
import com.park.energy.billing.PeriodType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Repository
public class CatalogRepository {

    private final JdbcTemplate jdbc;

    public CatalogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record ContractRow(long meterId, String rateCode, String tierCode) {}

    public ContractRow findContract(long meterId) {
        List<ContractRow> rows = jdbc.query(
                "select meter_id, rate_code, tier_code from meter_contract where meter_id = ?",
                (rs, n) -> new ContractRow(rs.getLong("meter_id"),
                        rs.getString("rate_code"), rs.getString("tier_code")),
                meterId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 取与账月区间相交的全部费率版本（含前后开放区间版本）。 */
    public List<RateVersionInfo> findRateVersions(String code, Instant from, Instant to) {
        List<RateVersionInfo> versions = jdbc.query("""
                select * from rate_version
                where code = ?
                  and (effective_to is null or effective_to > ?)
                  and effective_from < ?
                order by effective_from
                """,
                (rs, n) -> new RateVersionInfo(
                        rs.getLong("id"), rs.getString("code"), rs.getInt("version_no"),
                        rs.getTimestamp("effective_from").toInstant(),
                        rs.getTimestamp("effective_to") == null ? null
                                : rs.getTimestamp("effective_to").toInstant(),
                        rs.getBoolean("is_tou"), rs.getBigDecimal("flat_price"), List.of()),
                code, Timestamp.from(from), Timestamp.from(to));
        return versions.stream().map(v -> new RateVersionInfo(v.id(), v.code(), v.versionNo(),
                v.effectiveFrom(), v.effectiveTo(), v.tou(), v.flatPrice(),
                findTouRules(v.id()))).toList();
    }

    private List<TouRule> findTouRules(long versionId) {
        return jdbc.query(
                "select * from tou_period where rate_version_id = ? order by start_min",
                (rs, n) -> new TouRule(PeriodType.valueOf(rs.getString("period_type")),
                        rs.getInt("dow_mask"), rs.getInt("start_min"), rs.getInt("end_min"),
                        rs.getBigDecimal("price")),
                versionId);
    }

    /** 按账月第一天选生效阶梯方案。 */
    public TierScheduleInfo findTierForMonth(String code, LocalDate month) {
        List<Long> ids = jdbc.query("""
                select id from tier_schedule
                where code = ? and effective_from <= ?
                  and (effective_to is null or effective_to > ?)
                order by version_no desc
                """,
                (rs, n) -> List.of(rs.getLong("id")),
                code, month, month).stream().flatMap(List::stream).toList();
        if (ids.isEmpty()) {
            return null;
        }
        long id = ids.get(0);
        return jdbc.queryForObject("""
                select * from tier_schedule where id = ?
                """, (rs, n) -> {
            List<TierScheduleInfo.Step> steps = jdbc.query(
                    "select * from tier_step where tier_schedule_id = ? order by step_no",
                    (srs, sn) -> new TierScheduleInfo.Step(srs.getInt("step_no"),
                            srs.getBigDecimal("lower_kwh"), srs.getBigDecimal("upper_kwh"),
                            srs.getBigDecimal("price")), id);
            return new TierScheduleInfo(id, rs.getString("code"), rs.getInt("version_no"),
                    rs.getDate("effective_from").toLocalDate(),
                    rs.getDate("effective_to") == null ? null
                            : rs.getDate("effective_to").toLocalDate(),
                    TierScheduleInfo.PricingMode.valueOf(rs.getString("pricing_mode")), steps);
        }, id);
    }

    public List<RateVersionMeta> listRateVersions() {
        return jdbc.query("""
                select id, code, version_no, effective_from, effective_to, is_tou, flat_price, note
                from rate_version order by code, version_no
                """,
                (rs, n) -> new RateVersionMeta(rs.getLong("id"), rs.getString("code"),
                        rs.getInt("version_no"), rs.getTimestamp("effective_from").toInstant(),
                        rs.getTimestamp("effective_to") == null ? null
                                : rs.getTimestamp("effective_to").toInstant(),
                        rs.getBoolean("is_tou"), rs.getBigDecimal("flat_price"),
                        rs.getString("note")));
    }

    public List<TouRuleRow> listTouPeriods(long versionId) {
        return jdbc.query("select * from tou_period where rate_version_id = ? order by start_min",
                (rs, n) -> new TouRuleRow(versionId,
                        PeriodType.valueOf(rs.getString("period_type")), rs.getInt("dow_mask"),
                        rs.getInt("start_min"), rs.getInt("end_min"), rs.getBigDecimal("price")),
                versionId);
    }

    public record RateVersionMeta(long id, String code, int versionNo, Instant effectiveFrom,
                                  Instant effectiveTo, boolean tou, BigDecimal flatPrice, String note) {}

    public record TouRuleRow(long versionId, PeriodType type, int dowMask,
                             int startMin, int endMin, BigDecimal price) {}
}
