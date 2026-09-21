package com.park.energy.web;

import com.park.energy.repository.CatalogRepository;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/** 费率日历：版本生效区间时间轴 + 每版本 TOU 时段（含跨午夜标记）。 */
@RestController
@RequestMapping("/api/rates")
public class RateCalendarController {

    private final CatalogRepository catalogRepo;

    public RateCalendarController(CatalogRepository catalogRepo) {
        this.catalogRepo = catalogRepo;
    }

    public record CalendarResponse(String timezone, List<CatalogRepository.RateVersionMeta> versions,
                                   List<VersionDetail> details) {}

    public record VersionDetail(CatalogRepository.RateVersionMeta version,
                                List<CatalogRepository.TouRuleRow> periods) {}

    @GetMapping("/calendar")
    public CalendarResponse calendar(@RequestParam(required = false) String month) {
        ZoneId tz = ZoneId.of("Asia/Shanghai");
        if (month != null) {
            LocalDate.parse(month + "-01"); // 校验格式
        }
        List<CatalogRepository.RateVersionMeta> metas = catalogRepo.listRateVersions();
        List<VersionDetail> details = metas.stream()
                .map(vm -> new VersionDetail(vm, catalogRepo.listTouPeriods(vm.id()))).toList();
        return new CalendarResponse(tz.getId(), metas, details);
    }
}
