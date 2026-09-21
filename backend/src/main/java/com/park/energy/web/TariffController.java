package com.park.energy.web;

import com.park.energy.domain.CalendarDay;
import com.park.energy.domain.TariffVersion;
import com.park.energy.repo.CalendarDayRepository;
import com.park.energy.service.TariffService;
import com.park.energy.web.dto.Dtos.*;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

@RestController
@RequestMapping("/api")
public class TariffController {

    private final TariffService tariffService;
    private final CalendarDayRepository calendarRepo;

    public TariffController(TariffService tariffService, CalendarDayRepository calendarRepo) {
        this.tariffService = tariffService;
        this.calendarRepo = calendarRepo;
    }

    /** 费率日历：费率版本（含生效区间、尖峰平谷、月度阶梯）+ 指定月份的日期类型。 */
    @GetMapping("/tariffs")
    public List<TariffDto> tariffs() {
        return tariffService.allVersions().stream().map(this::toDto)
                .sorted(Comparator.comparing(TariffDto::effectiveFrom)).toList();
    }

    @GetMapping("/calendar")
    public List<CalendarDayDto> calendar(@RequestParam String month) {
        LocalDate first;
        try {
            first = LocalDate.parse(month + "-01");
        } catch (Exception e) {
            throw new IllegalArgumentException("month 格式应为 yyyy-MM");
        }
        LocalDate last = first.plusMonths(1).minusDays(1);
        return first.datesUntil(last.plusDays(1))
                .map(d -> new CalendarDayDto(d.toString(), tariffService.dayType(d).name()))
                .toList();
    }

    private TariffDto toDto(TariffVersion v) {
        List<TouDto> tou = v.getTouPeriods().stream()
                .map(p -> new TouDto(p.getPeriodType().name(), p.getPeriodType().getLabel(),
                        p.getStartMin(), p.getEndMin(), p.getPricePerKwh().toPlainString()))
                .toList();
        var domainTiers = v.getTierRules().stream()
                .sorted(Comparator.comparingInt(com.park.energy.domain.TierRule::getTierIndex)).toList();
        List<TierDto> tiers = domainTiers.stream().map(t -> {
            String lower = domainTiers.stream()
                    .filter(r -> r.getTierIndex() < t.getTierIndex())
                    .map(com.park.energy.domain.TierRule::getUpperKwh)
                    .filter(java.util.Objects::nonNull)
                    .max(java.math.BigDecimal::compareTo)
                    .map(java.math.BigDecimal::toPlainString).orElse("0");
            return new TierDto(t.getTierIndex(), lower,
                    t.getUpperKwh() == null ? null : t.getUpperKwh().toPlainString(),
                    t.getSurchargePerKwh().toPlainString());
        }).toList();
        return new TariffDto(v.getId(), v.getCode(), v.getEffectiveFrom(), v.getEffectiveTo(),
                v.getNote(), tou, tiers);
    }
}
