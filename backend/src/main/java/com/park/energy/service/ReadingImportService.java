package com.park.energy.service;

import com.park.energy.repository.MeterRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 本地读数文件导入（不接真实电网）。
 * CSV：meter_code,timestamp,reading_kwh
 * timestamp 支持 yyyy-MM-dd HH:mm:ss（按表计园区时区解释）或 ISO-8601 带偏移量。
 * (meter_id, ts) 唯一约束保证同一文件可重复导入（幂等 upsert）。
 */
@Service
public class ReadingImportService {

    private final MeterRepository meterRepo;

    public ReadingImportService(MeterRepository meterRepo) {
        this.meterRepo = meterRepo;
    }

    public record ImportReport(int totalRows, int inserted, int updated, int rejected,
                               List<String> errors) {
        public boolean ok() {
            return rejected == 0;
        }
    }

    public ImportReport importCsv(MultipartFile file) throws Exception {
        List<MeterRepository.ParsedReading> all = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        String currentCode = null;
        ZoneId tz = ZoneId.of("Asia/Shanghai");
        int lineNo = 0;
        int rejected = 0;

        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                lineNo++;
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                if (lineNo == 1 && line.toLowerCase().startsWith("meter_code")) {
                    continue; // 表头
                }
                String[] parts = line.split(",");
                if (parts.length < 3) {
                    errors.add("第" + lineNo + "行：列数不足");
                    rejected++;
                    continue;
                }
                String code = parts[0].trim();
                String tsRaw = parts[1].trim();
                String valRaw = parts[2].trim();
                try {
                    if (currentCode == null) {
                        currentCode = code;
                        MeterRepository.MeterRow meter = meterRepo.findByCode(code);
                        if (meter == null) {
                            throw new IllegalArgumentException("未知表计 " + code + "，请先登记表计与费率绑定");
                        }
                        tz = ZoneId.of(meter.tz());
                    } else if (!currentCode.equals(code)) {
                        throw new IllegalArgumentException("一次导入只能属于一个表计（发现 "
                                + currentCode + " 与 " + code + "）");
                    }
                    Instant ts = parseTs(tsRaw, tz);
                    BigDecimal val = new BigDecimal(valRaw);
                    if (val.signum() < 0 || val.scale() > 6) {
                        throw new IllegalArgumentException("读数必须非负且小数位<=6：" + valRaw);
                    }
                    all.add(new MeterRepository.ParsedReading(ts, val));
                } catch (Exception e) {
                    errors.add("第" + lineNo + "行：" + e.getMessage());
                    rejected++;
                }
            }
        }
        if (currentCode == null) {
            throw new IllegalArgumentException("文件中没有有效数据行");
        }
        // 同时间戳去重（保留最后值），避免违反唯一约束前自相矛盾
        java.util.SortedMap<Instant, BigDecimal> dedup = new java.util.TreeMap<>();
        for (MeterRepository.ParsedReading r : all) {
            dedup.put(r.ts(), r.readingKwh());
        }
        List<MeterRepository.ParsedReading> rows = dedup.entrySet().stream()
                .map(e -> new MeterRepository.ParsedReading(e.getKey(), e.getValue())).toList();

        MeterRepository.MeterRow meter = meterRepo.findByCode(currentCode);
        MeterRepository.UpsertResult res = meterRepo.upsertReadings(
                meter.id(), rows, file.getOriginalFilename());
        return new ImportReport(rows.size(), res.inserted(), res.updated(), rejected, errors);
    }

    private static final java.util.regex.Pattern OFFSET_RE =
            java.util.regex.Pattern.compile(".*[+-]\\d{2}:?\\d{2}$|.*Z$");

    private Instant parseTs(String raw, ZoneId tz) {
        String s = raw.replace('/', '-').trim();
        // 带时区偏移/祖鲁时（如 2026-03-10 00:00:00+08:00、…T…Z）。
        // 注意：不能用 lastIndexOf('-') 找符号，日期里的连字符会干扰判断。
        if (s.contains("T") || OFFSET_RE.matcher(s).matches()) {
            return java.time.OffsetDateTime.parse(s.replace(' ', 'T')).toInstant();
        }
        // 无时区的 yyyy-MM-dd HH:mm:ss：按表计园区固定时区解释
        return LocalDateTime.parse(s, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                .atZone(tz).toInstant();
    }
}
