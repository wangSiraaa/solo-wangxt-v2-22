package com.park.energy.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 幂等种子：登记表计、费率版本（含跨月调价）、阶梯方案。
 * 重复调用不会产生重复版本；只做「不存在才插入」。
 */
@Service
public class SeedService {

    private final JdbcTemplate jdbc;

    public SeedService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public String seed() {
        ZoneId tz = ZoneId.of("Asia/Shanghai");
        meter("M01", "1号厂房总表(尖峰平谷)", 60);
        meter("M02", "2号厂房总表(跨月调价+阶梯加价)", 60);
        meter("M03", "3号厂房总表(阶梯全额-恰好越界)", 60);
        meter("M04", "4号仓库表(缺读数演示)", 60);

        // M01：全年单一 TOU 版本
        long v = rateVersion("TOU_DEFAULT", 1, ts("2026-01-01", tz), null, true, null,
                "尖峰平谷基准价");
        touIfEmpty(v);

        // M02：3/15 00:00 调价 → 两个生效区间版本
        long v1 = rateVersion("TOU_MIDMONTH", 1, ts("2026-01-01", tz), ts("2026-03-15", tz),
                true, null, "调价前");
        tou(v1, "1.00", "0.80", "0.60", "0.40");
        long v2 = rateVersion("TOU_MIDMONTH", 2, ts("2026-03-15", tz), null, true, null,
                "3月15日起上调");
        tou(v2, "1.20", "1.00", "0.70", "0.45");

        // M03/M04：单一价
        rateVersion("FLAT_STD", 1, ts("2026-01-01", tz), null, false, new BigDecimal("0.99"),
                "阶梯全额模式下不直接计费（被阶梯替代）");
        rateVersion("FLAT_080", 1, ts("2026-01-01", tz), null, false, new BigDecimal("0.80"),
                "平段单一价");

        // 阶梯：M02 加价档；M03 全额替代档
        tier("TIER_SURCHARGE", 1, "2026-01-01", null, "SURCHARGE",
                new Object[][]{{1, "0", "200", "0.10"}, {2, "200", "400", "0.20"},
                        {3, "400", null, "0.30"}});
        tier("TIER_REPLACE", 1, "2026-01-01", null, "REPLACE",
                new Object[][]{{1, "0", "500", "0.80"}, {2, "500", null, "1.00"}});

        contract("M01", "TOU_DEFAULT", null);
        contract("M02", "TOU_MIDMONTH", "TIER_SURCHARGE");
        contract("M03", "FLAT_STD", "TIER_REPLACE");
        contract("M04", "FLAT_080", null);
        return "seeded";
    }

    private void meter(String code, String name, int interval) {
        jdbc.update("""
                insert into meter(meter_code, display_name, park_tz, expected_interval_minutes)
                values (?,?,?,?) on conflict (meter_code) do nothing
                """, code, name, "Asia/Shanghai", interval);
    }

    private long rateVersion(String code, int versionNo, Timestamp from, Timestamp to,
                             boolean tou, BigDecimal flat, String note) {
        jdbc.update("""
                insert into rate_version(code, version_no, effective_from, effective_to,
                                         is_tou, flat_price, note)
                values (?,?,?,?,?,?,?) on conflict (code, version_no) do nothing
                """, code, versionNo, from, to, tou, flat, note);
        return jdbc.queryForObject(
                "select id from rate_version where code=? and version_no=?",
                Long.class, code, versionNo);
    }

    private static final int ALL_DAYS = 0b1111111;

    private void touIfEmpty(long versionId) {
        Integer cnt = jdbc.queryForObject(
                "select count(*) from tou_period where rate_version_id=?", Integer.class, versionId);
        if (cnt != null && cnt > 0) {
            return;
        }
        tou(versionId, "1.20", "0.90", "0.60", "0.30");
    }

    /** 上海口径示例：尖 10-12,13-15；峰 8-10,15-21；平 6-8,12-13,21-22；谷 22-06(跨午夜)。 */
    private void tou(long versionId, String sharp, String peak, String flat, String valley) {
        jdbc.update("delete from tou_period where rate_version_id=?", versionId);
        period(versionId, "SHARP", ALL_DAYS, 600, 720, sharp);
        period(versionId, "SHARP", ALL_DAYS, 780, 900, sharp);
        period(versionId, "PEAK", ALL_DAYS, 480, 600, peak);
        period(versionId, "PEAK", ALL_DAYS, 900, 1260, peak);
        period(versionId, "FLAT", ALL_DAYS, 360, 480, flat);
        period(versionId, "FLAT", ALL_DAYS, 720, 780, flat);
        period(versionId, "FLAT", ALL_DAYS, 1260, 1320, flat);
        period(versionId, "VALLEY", ALL_DAYS, 1320, 360, valley); // 22:00–06:00 跨午夜
    }

    private void period(long versionId, String type, int mask, int start, int end, String price) {
        jdbc.update("""
                insert into tou_period(rate_version_id, period_type, dow_mask, start_min, end_min, price)
                values (?,?,?,?,?,?)
                """, versionId, type, mask, start, end, new BigDecimal(price));
    }

    private void tier(String code, int versionNo, String from, String to, String mode,
                      Object[][] steps) {
        jdbc.update("""
                insert into tier_schedule(code, version_no, effective_from, effective_to, pricing_mode)
                values (?,?,?,?,?) on conflict (code, version_no) do nothing
                """, code, versionNo, LocalDate.parse(from), to == null ? null : LocalDate.parse(to),
                mode);
        Long id = jdbc.queryForObject(
                "select id from tier_schedule where code=? and version_no=?", Long.class, code, versionNo);
        Integer cnt = jdbc.queryForObject(
                "select count(*) from tier_step where tier_schedule_id=?", Integer.class, id);
        if (cnt != null && cnt > 0) {
            return;
        }
        for (Object[] s : steps) {
            jdbc.update("""
                    insert into tier_step(tier_schedule_id, step_no, lower_kwh, upper_kwh, price)
                    values (?,?,?,?,?)
                    """, id, s[0], new BigDecimal((String) s[1]),
                    s[2] == null ? null : new BigDecimal((String) s[2]), new BigDecimal((String) s[3]));
        }
    }

    private void contract(String meterCode, String rateCode, String tierCode) {
        Long meterId = jdbc.queryForObject("select id from meter where meter_code=?",
                Long.class, meterCode);
        jdbc.update("""
                insert into meter_contract(meter_id, rate_code, tier_code)
                values (?,?,?)
                on conflict (meter_id) do update set rate_code=excluded.rate_code,
                                                      tier_code=excluded.tier_code
                """, meterId, rateCode, tierCode);
    }

    private Timestamp ts(String date, ZoneId tz) {
        return Timestamp.from(LocalDate.parse(date).atStartOfDay(tz).toInstant());
    }
}
