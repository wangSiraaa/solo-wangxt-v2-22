package com.park.energy.init;

import com.park.energy.domain.*;
import com.park.energy.repo.*;
import com.park.energy.service.AppProperties;
import com.park.energy.service.ReadingImportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.function.Supplier;

/**
 * 幂等播种：
 * - 两块样本表计 M-001（1 小时抄表）/ M-002（90 分钟抄表）
 * - 三个费率版本 v1 / v2 / v3，覆盖跨月调价（3/1）与月中调价（3/15）
 * - 费率日历：样本日期显式标记为工作日（覆盖周末默认规则，体现日历权威）
 * - 样本读数 CSV 同时写到本地 data/samples，可通过导入接口重复导入（按 SHA-256 幂等）
 */
@Component
public class DataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final MeterRepository meterRepo;
    private final TariffVersionRepository tariffRepo;
    private final CalendarDayRepository calendarRepo;
    private final ReadingImportService importService;
    private final TransactionTemplate tx;
    private final AppProperties props;

    public DataInitializer(MeterRepository meterRepo, TariffVersionRepository tariffRepo,
                           CalendarDayRepository calendarRepo, ReadingImportService importService,
                           TransactionTemplate tx, AppProperties props) {
        this.meterRepo = meterRepo;
        this.tariffRepo = tariffRepo;
        this.calendarRepo = calendarRepo;
        this.importService = importService;
        this.tx = tx;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!props.getSeed().isEnabled()) {
            return;
        }
        ZoneId zone = ZoneId.of(props.getTimezone());
        Instant now = Instant.now();

        tx.executeWithoutResult(s -> {
            seedMeter("m-001", "M-001", "园区1号总表（2h抄表/跨午夜+跨月调价/含缺失）", "ZONE-A", zone, 7200, now);
            seedMeter("m-002", "M-002", "园区2号分表（月中调价/越档样本）", "ZONE-A", zone, 5400, now);

            if (tariffRepo.count() == 0) {
                seedTariffs(zone, now);
            }

            // 日历：2026-02 与 2026-03 样本窗口全部标记为工作日（即使落在周末，日历为权威来源）
            List<LocalDate> dates = List.of(
                    LocalDate.of(2026, 2, 27), LocalDate.of(2026, 2, 28),
                    LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 2),
                    LocalDate.of(2026, 3, 14), LocalDate.of(2026, 3, 15), LocalDate.of(2026, 3, 16));
            int seq = 0;
            for (LocalDate d : dates) {
                if (!calendarRepo.existsByLocalDate(d)) {
                    calendarRepo.save(new CalendarDay("cal-" + d, d, DayType.WORKDAY));
                }
            }
        });

        // 样本读数走正式导入通道（批次/哈希/幂等与手工导入完全一致）
        seedReadings("samples/m001_readings.csv", "m001_readings.csv");
        seedReadings("samples/m002_readings.csv", "m002_readings.csv");
    }

    private void seedMeter(String id, String code, String name, String zoneCode, ZoneId zone, int cadence, Instant now) {
        meterRepo.findByCode(code).orElseGet(() -> {
            Meter m = new Meter(id, code, name, zoneCode, zone.getId(), cadence, now);
            meterRepo.save(m);
            return m;
        });
    }

    private void seedTariffs(ZoneId zone, Instant now) {
        TariffVersion v1 = new TariffVersion("tar-v1", "V2025-STD",
                LocalDate.of(2025, 1, 1).atStartOfDay(zone).toInstant(),
                LocalDate.of(2026, 3, 1).atStartOfDay(zone).toInstant(),
                "基础费率（2026-03-01 前）", now);
        addTou(v1, "tou-v1-", PeriodType.SHARP, 1200, 1320, "1.20");
        addTou(v1, "tou-v1-", PeriodType.FLAT, 360, 480, "0.70");
        addTou(v1, "tou-v1-", PeriodType.FLAT, 480, 1200, "0.70");
        addTou(v1, "tou-v1-", PeriodType.VALLEY, 1320, 360, "0.35"); // 22:00-次日06:00 跨午夜
        addTier(v1, "tier-v1-", 1, 1000, "0");
        tariffRepo.save(v1);

        TariffVersion v2 = new TariffVersion("tar-v2", "V2026-03-PREMIUM",
                LocalDate.of(2026, 3, 1).atStartOfDay(zone).toInstant(),
                LocalDate.of(2026, 3, 15).atStartOfDay(zone).toInstant(),
                "3 月调价（尖峰上浮、谷段上浮），启用月度阶梯", now);
        addTou(v2, "tou-v2-", PeriodType.SHARP, 1200, 1320, "1.3025");
        addTou(v2, "tou-v2-", PeriodType.FLAT, 360, 480, "0.753");
        addTou(v2, "tou-v2-", PeriodType.FLAT, 480, 1200, "0.753");
        addTou(v2, "tou-v2-", PeriodType.VALLEY, 1320, 360, "0.40");
        addTier(v2, "tier-v2-", 1, 50, "0");
        addTier(v2, "tier-v2-", 2, null, "0.10");
        tariffRepo.save(v2);

        TariffVersion v3 = new TariffVersion("tar-v3", "V2026-03MID-ADJ",
                LocalDate.of(2026, 3, 15).atStartOfDay(zone).toInstant(),
                null, "3 月 15 日月中调价（谷段 0.42、尖峰 1.35、平段 0.78），阶梯加价调整", now);
        addTou(v3, "tou-v3-", PeriodType.SHARP, 1200, 1320, "1.35");
        addTou(v3, "tou-v3-", PeriodType.FLAT, 360, 480, "0.78");
        addTou(v3, "tou-v3-", PeriodType.FLAT, 480, 1200, "0.78");
        addTou(v3, "tou-v3-", PeriodType.VALLEY, 1320, 360, "0.42");
        addTier(v3, "tier-v3-", 1, 50, "0");
        addTier(v3, "tier-v3-", 2, null, "0.12");
        tariffRepo.save(v3);
    }

    private void addTou(TariffVersion v, String idPrefix, PeriodType type, int start, int end, String price) {
        v.getTouPeriods().add(new TouPeriod(idPrefix + type.name().toLowerCase() + "-" + start + "-" + end,
                v, type, start, end, new BigDecimal(price),
                type == PeriodType.VALLEY ? "跨午夜谷段" : null));
    }

    private void addTier(TariffVersion v, String idPrefix, int index, Integer upperKwh, String surcharge) {
        v.getTierRules().add(new TierRule(idPrefix + index, v, index,
                upperKwh == null ? null : new BigDecimal(upperKwh), new BigDecimal(surcharge)));
    }

    private void seedReadings(String classpath, String fileName) {
        try {
            byte[] bytes = new ClassPathResource(classpath).getInputStream().readAllBytes();
            Path outDir = Paths.get(props.getSeed().getSamplesDir());
            Files.createDirectories(outDir);
            Path out = outDir.resolve(fileName);
            if (!Files.exists(out)) {
                Files.write(out, bytes, StandardOpenOption.CREATE_NEW);
            }
            var result = importService.importCsv(fileName, bytes);
            log.info("样本读数 {}: 导入 {} 行, 重复 {} 行{}",
                    fileName, result.importedRows(), result.duplicateRows(),
                    result.reused() ? "（文件已导入过，幂等命中）" : "");
        } catch (IOException e) {
            throw new IllegalStateException("无法读取 classpath 样本 " + classpath, e);
        } catch (RuntimeException e) {
            // 非法样本是构建期问题，必须显式暴露
            throw new IllegalStateException("样本读数导入失败 " + fileName + ": " + e.getMessage(), e);
        }
    }
}
