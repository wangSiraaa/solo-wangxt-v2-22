package com.park.energy.web;

import com.park.energy.service.ReadingImportService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/meters")
public class MeterImportController {

    private final ReadingImportService importService;

    public MeterImportController(ReadingImportService importService) {
        this.importService = importService;
    }

    /** 可重复导入：按 (meter, ts) 幂等 upsert。 */
    @PostMapping("/import")
    public ResponseEntity<ReadingImportService.ImportReport> importReadings(
            @RequestParam("file") MultipartFile file) throws Exception {
        return ResponseEntity.ok(importService.importCsv(file));
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, String>> handle(RuntimeException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
