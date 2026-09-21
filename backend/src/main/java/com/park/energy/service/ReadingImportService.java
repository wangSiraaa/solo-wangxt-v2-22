package com.park.energy.service;

import com.park.energy.domain.ImportBatch;
import com.park.energy.domain.Meter;
import com.park.energy.domain.MeterReading;
import com.park.energy.repo.ImportBatchRepository;
import com.park.energy.repo.MeterReadingRepository;
import com.park.energy.repo.MeterRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 本地读数文件导入（CSV：meterCode,ts,readingKwh，ts 为 ISO_OFFSET_DATE_TIME 或 yyyy-MM-ddTHH:mm:ss，
 * 后者按园区固定时区解释）。按文件 SHA-256 幂等：同一文件重复导入直接返回首次批次。
 */
@Service
public class ReadingImportService {

    private static final DateTimeFormatter LOCAL_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final MeterRepository meterRepo;
    private final MeterReadingRepository readingRepo;
    private final ImportBatchRepository batchRepo;
    private final ZoneId zone;

    public ReadingImportService(MeterRepository meterRepo, MeterReadingRepository readingRepo,
                                ImportBatchRepository batchRepo, AppProperties props) {
        this.meterRepo = meterRepo;
        this.readingRepo = readingRepo;
        this.batchRepo = batchRepo;
        this.zone = ZoneId.of(props.timezone());
    }

    public record ImportResult(boolean reused, String batchId, String fileName, String sha256,
                               int rowCount, int importedRows, int duplicateRows, int invalidRows,
                               List<String> errors) {}

    @Transactional
    public ImportResult importCsv(String fileName, byte[] content) {
        String sha = sha256Hex(content);
        Optional<ImportBatch> existing = batchRepo.findByContentSha256(sha);
        if (existing.isPresent()) {
            ImportBatch b = existing.get();
            return new ImportResult(true, b.getId(), b.getFileName(), sha, b.getRowCount(),
                    b.getImportedRows(), b.getDuplicateRows(), b.getInvalidRows(), splitErrors(b.getErrors()));
        }

        String text = new String(content, StandardCharsets.UTF_8);
        List<String> lines = text.lines().toList();
        List<String> errors = new ArrayList<>();
        int rowCount = 0, imported = 0, duplicate = 0, invalid = 0;
        List<MeterReading> pending = new ArrayList<>();
        String batchId = UUID.randomUUID().toString();
        Instant now = Instant.now();

        int lineNo = 0;
        for (String rawLine : lines) {
            lineNo++;
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (lineNo == 1 && line.toLowerCase(Locale.ROOT).startsWith("meter")) {
                continue; // 表头
            }
            rowCount++;
            String[] parts = line.split(",", -1);
            if (parts.length != 3) {
                invalid++;
                errors.add("第" + lineNo + "行：列数应为3（meterCode,ts,readingKwh）");
                continue;
            }
            String meterCode = parts[0].trim();
            Instant ts;
            BigDecimal value;
            try {
                Optional<Meter> meter = meterRepo.findByCode(meterCode);
                if (meter.isEmpty()) {
                    invalid++;
                    errors.add("第" + lineNo + "行：未知表计 " + meterCode);
                    continue;
                }
                ts = parseTs(parts[1].trim());
                value = new BigDecimal(parts[2].trim()).stripTrailingZeros();
                if (value.signum() < 0) {
                    invalid++;
                    errors.add("第" + lineNo + "行：表码不得为负");
                    continue;
                }
                if (readingRepo.countAt(meter.get().getId(), ts) > 0) {
                    duplicate++;
                    continue;
                }
                pending.add(new MeterReading(UUID.randomUUID().toString(), meter.get().getId(),
                        ts, value.setScale(6, java.math.RoundingMode.HALF_UP), batchId, now));
                imported++;
            } catch (Exception ex) {
                invalid++;
                errors.add("第" + lineNo + "行：" + ex.getMessage());
            }
        }

        String errorText = String.join("\n", errors);
        ImportBatch batch = new ImportBatch(batchId, fileName, sha, rowCount,
                invalid == 0 ? imported : 0, invalid == 0 ? duplicate : 0, invalid,
                errorText == null || errorText.isEmpty() ? null : errorText, now);
        batchRepo.save(batch);

        if (invalid == 0) {
            readingRepo.saveAll(pending);
        } else {
            errors.add(0, "存在 " + invalid + " 行非法数据，本文件整批回滚，未写入任何读数（缺失/非法数据绝不静默置零）");
        }

        if (invalid > 0) {
            throw new IllegalArgumentException(errorText);
        }
        return new ImportResult(false, batchId, fileName, sha, rowCount, imported, duplicate, 0, List.of());
    }

    private Instant parseTs(String s) {
        // 带偏移/带时区直接解析
        try {
            return OffsetDateTime.parse(s).toInstant();
        } catch (Exception ignored) {
            // fall through
        }
        try {
            return Instant.parse(s);
        } catch (Exception ignored) {
            // fall through
        }
        // 本地时间按园区固定时区解释
        return LocalDateTime.parse(s, LOCAL_FMT).atZone(zone).toInstant();
    }

    private String sha256Hex(byte[] content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(content);
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private List<String> splitErrors(String errors) {
        if (errors == null || errors.isEmpty()) {
            return List.of();
        }
        return new BufferedReader(new StringReader(errors)).lines().toList();
    }
}
