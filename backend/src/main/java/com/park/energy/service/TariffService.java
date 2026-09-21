package com.park.energy.service;

import com.park.energy.billing.BillingException;
import com.park.energy.billing.BillingModels.Tariff;
import com.park.energy.billing.BillingModels.TierBand;
import com.park.energy.billing.BillingModels.TouRule;
import com.park.energy.domain.CalendarDay;
import com.park.energy.domain.DayType;
import com.park.energy.domain.TariffVersion;
import com.park.energy.repo.CalendarDayRepository;
import com.park.energy.repo.TariffVersionRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;

@Service
public class TariffService {

    private final TariffVersionRepository tariffRepo;
    private final CalendarDayRepository calendarRepo;
    private final ZoneId zone;

    public TariffService(TariffVersionRepository tariffRepo, CalendarDayRepository calendarRepo,
                         AppProperties props) {
        this.tariffRepo = tariffRepo;
        this.calendarRepo = calendarRepo;
        this.zone = ZoneId.of(props.timezone());
    }

    public List<TariffVersion> versionsOverlapping(Instant start, Instant end) {
        return tariffRepo.findOverlappingWithFetch(start, end);
    }

    public List<TariffVersion> allVersions() {
        return tariffRepo.findAllWithFetch();
    }

    public List<Tariff> tariffsOverlapping(Instant start, Instant end) {
        return tariffRepo.findOverlappingWithFetch(start, end).stream().map(this::toModel).toList();
    }

    public Tariff toModel(TariffVersion v) {
        List<TouRule> tou = v.getTouPeriods().stream()
                .map(p -> new TouRule(p.getPeriodType(), p.getStartMin(), p.getEndMin(), p.getPricePerKwh()))
                .toList();
        // 档位按下界链式排列：第 1 档下界 0，其后各档下界 = 前一档上界
        List<com.park.energy.domain.TierRule> sorted = v.getTierRules().stream()
                .sorted(java.util.Comparator.comparingInt(com.park.energy.domain.TierRule::getTierIndex))
                .toList();
        List<TierBand> tiers = new java.util.ArrayList<>();
        BigDecimal lower = BigDecimal.ZERO;
        for (com.park.energy.domain.TierRule t : sorted) {
            tiers.add(new TierBand(t.getTierIndex(), lower, t.getUpperKwh(), t.getSurchargePerKwh()));
            if (t.getUpperKwh() != null) {
                lower = t.getUpperKwh();
            }
        }
        return new Tariff(v.getId(), v.getCode(), v.getEffectiveFrom(), v.getEffectiveTo(), tou, tiers);
    }

    /**
     * 日历口径：优先查费率日历显式标记；未标记时回退到固定规则（周六日=WEEKEND，其余=WORKDAY）。
     * 费率日历是权威来源，回退只用于演示便利并在响应中体现 dayType。
     */
    public DayType dayType(LocalDate date) {
        return calendarRepo.findByLocalDate(date)
                .map(CalendarDay::getDayType)
                .orElseGet(() -> {
                    DayOfWeek dow = date.getDayOfWeek();
                    return (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY)
                            ? DayType.WEEKEND : DayType.WORKDAY;
                });
    }

    /** 校验一组费率版本在 [s,e) 内连续且不重叠（计费引擎再兜底）。 */
    public void assertContinuousCoverage(List<Tariff> tariffs, Instant s, Instant e) {
        Instant cursor = s;
        List<Tariff> sorted = tariffs.stream()
                .sorted(java.util.Comparator.comparing(Tariff::effectiveFrom)).toList();
        for (Tariff t : sorted) {
            Instant from = max(t.effectiveFrom(), s);
            if (from.isAfter(cursor)) {
                throw new BillingException("费率版本在 [" + cursor + ", " + from + ") 存在缺口");
            }
            Instant to = t.effectiveTo() == null ? e : min(t.effectiveTo(), e);
            if (to.compareTo(cursor) < 0) {
                throw new BillingException("费率版本重叠：" + t.code());
            }
            cursor = to;
        }
        if (cursor.isBefore(e)) {
            throw new BillingException("费率版本未覆盖账期到 " + e);
        }
    }

    public YearMonth ym(String billingMonth) {
        try {
            return YearMonth.parse(billingMonth);
        } catch (Exception ex) {
            throw new BillingException("账期格式必须为 yyyy-MM：" + billingMonth);
        }
    }

    public Instant monthStart(YearMonth ym) {
        return ym.atDay(1).atStartOfDay(zone).toInstant();
    }

    public Instant monthEnd(YearMonth ym) {
        return ym.atEndOfMonth().plusDays(1).atStartOfDay(zone).toInstant();
    }

    private static Instant max(Instant a, Instant b) { return a.isAfter(b) ? a : b; }
    private static Instant min(Instant a, Instant b) { return a.isBefore(b) ? a : b; }
}
