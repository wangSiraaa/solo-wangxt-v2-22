package com.park.energy.web;

import com.park.energy.domain.Meter;
import com.park.energy.domain.MeterReading;
import com.park.energy.repo.MeterReadingRepository;
import com.park.energy.repo.MeterRepository;
import com.park.energy.service.AppProperties;
import com.park.energy.web.dto.Dtos.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api")
public class MeterController {

    private final MeterRepository meterRepo;
    private final MeterReadingRepository readingRepo;
    private final com.park.energy.service.ReadingImportService importService;
    private final AppProperties props;

    public MeterController(MeterRepository meterRepo, MeterReadingRepository readingRepo,
                           com.park.energy.service.ReadingImportService importService, AppProperties props) {
        this.meterRepo = meterRepo;
        this.readingRepo = readingRepo;
        this.importService = importService;
        this.props = props;
    }

    @GetMapping("/meta")
    public MetaDto meta() {
        return new MetaDto(props.getTimezone(), props.getBillingCurrency(),
                props.missingGapFactor());
    }

    public record MetaDto(String timezone, String currency, double missingGapFactor) {}

    @GetMapping("/meters")
    public List<MeterDto> meters() {
        return meterRepo.findAll().stream()
                .map(m -> new MeterDto(m.getId(), m.getCode(), m.getName(), m.getTimezone(),
                        m.getExpectedCadenceSeconds()))
                .toList();
    }

    @GetMapping("/meters/{code}/curve")
    public CurveDto curve(@PathVariable String code,
                          @RequestParam(required = false) Instant from,
                          @RequestParam(required = false) Instant to) {
        Meter meter = meterRepo.findByCode(code).orElseThrow(() -> new IllegalArgumentException("未知表计 " + code));
        Instant minTs = readingRepo.findMinTs(meter.getId()).orElse(null);
        Instant maxTs = readingRepo.findMaxTs(meter.getId()).orElse(null);
        Instant f = from != null ? from : minTs;
        Instant t = to != null ? to : maxTs;
        List<ReadingPointDto> points;
        List<CurveGapDto> gaps = List.of();
        if (f != null && t != null && !t.isBefore(f)) {
            List<MeterReading> rows = readingRepo.findWindow(meter.getId(), f, t);
            points = rows.stream().map(r -> new ReadingPointDto(r.getTs(), r.getReadingKwh().toPlainString())).toList();
            Duration maxGap = Duration.ofSeconds((long) (meter.getExpectedCadenceSeconds() * props.missingGapFactor()));
            gaps = detectGaps(rows, maxGap);
        } else {
            points = List.of();
        }
        return new CurveDto(code, points, gaps, minTs, maxTs);
    }

    private List<CurveGapDto> detectGaps(List<MeterReading> rows, Duration maxGap) {
        java.util.List<CurveGapDto> result = new java.util.ArrayList<>();
        for (int i = 1; i < rows.size(); i++) {
            Instant a = rows.get(i - 1).getTs();
            Instant b = rows.get(i).getTs();
            if (Duration.between(a, b).compareTo(maxGap) > 0) {
                result.add(new CurveGapDto(a, b, "MISSING_READING",
                        "间隔 " + Duration.between(a, b).toSeconds() + "s 超过 " + maxGap.toSeconds()
                                + "s，缺失，不当零"));
            }
        }
        return result;
    }

    @PostMapping(path = "/readings/import", consumes = "multipart/form-data")
    public ImportResultDto importReadings(@RequestParam("file") MultipartFile file) throws IOException {
        var r = importService.importCsv(file.getOriginalFilename(), file.getBytes());
        return new ImportResultDto(r.reused(), r.batchId(), r.fileName(), r.sha256(),
                r.rowCount(), r.importedRows(), r.duplicateRows(), r.invalidRows(), r.errors());
    }
}
