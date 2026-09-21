package com.park.energy.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.park.energy.billing.BillingEngine;
import com.park.energy.billing.BillingException;
import com.park.energy.billing.BillingModels.*;
import com.park.energy.domain.*;
import com.park.energy.repo.*;
import com.park.energy.web.dto.Dtos.BillPreviewDto;
import com.park.energy.web.dto.Dtos.BillSummaryDto;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

@Service
public class BillingService {

    private final MeterRepository meterRepo;
    private final MeterReadingRepository readingRepo;
    private final TariffService tariffService;
    private final BillRepository billRepo;
    private final BillLineRepository lineRepo;
    private final BillFragmentRepository fragmentRepo;
    private final BillTierAllocRepository allocRepo;
    private final BillGapRepository gapRepo;
    private final TariffVersionRepository tariffVersionRepo;
    private final ObjectMapper objectMapper;
    private final AppProperties props;
    private final ZoneId zone;

    public BillingService(MeterRepository meterRepo, MeterReadingRepository readingRepo,
                          TariffService tariffService, BillRepository billRepo,
                          BillLineRepository lineRepo, BillFragmentRepository fragmentRepo,
                          BillTierAllocRepository allocRepo, BillGapRepository gapRepo,
                          TariffVersionRepository tariffVersionRepo, ObjectMapper objectMapper,
                          AppProperties props) {
        this.meterRepo = meterRepo;
        this.readingRepo = readingRepo;
        this.tariffService = tariffService;
        this.billRepo = billRepo;
        this.lineRepo = lineRepo;
        this.fragmentRepo = fragmentRepo;
        this.allocRepo = allocRepo;
        this.gapRepo = gapRepo;
        this.tariffVersionRepo = tariffVersionRepo;
        this.objectMapper = objectMapper;
        this.props = props;
        this.zone = ZoneId.of(props.timezone());
    }

    /** 试算（不落库）。返回完整计算明细，供界面对照手工算例。 */
    @Transactional(readOnly = true)
    public BillPreviewDto preview(String meterCode, String billingMonth) {
        Context ctx = loadContext(meterCode, billingMonth);
        tariffService.assertContinuousCoverage(ctx.tariffs, ctx.start, ctx.end);
        BillingEngine engine = new BillingEngine(zone, tariffService::dayType,
                Duration.ofSeconds((long) (ctx.meter.getExpectedCadenceSeconds() * props.missingGapFactor())));
        Result result = engine.calculate(ctx.start, ctx.end, ctx.readings, ctx.tariffs);
        Basis basis = buildBasis(ctx, result);
        return Assembler.toPreview(ctx, result, basis.hash(), basis.snapshotJson());
    }

    /**
     * 确认账单：把当时计算依据（费率版本/时段/阶梯/日历/参与读数/缺口/指纹）整体冻结到账单快照。
     * 同一表计同一账期只能确认一次；重复确认返回已确认账单（幂等）。
     */
    @Transactional
    public BillPreviewDto confirm(String meterCode, String billingMonth) {
        Optional<Bill> existing = billRepo.findByMeterIdAndBillingMonth(
                meterRepo.findByCode(meterCode).orElseThrow(() -> new BillingException("未知表计 " + meterCode)).getId(),
                billingMonth);
        Context ctx = loadContext(meterCode, billingMonth);
        if (existing.isPresent()) {
            return assemblePersisted(existing.get(), ctx.meterCode(), ctx.meterName());
        }

        tariffService.assertContinuousCoverage(ctx.tariffs, ctx.start, ctx.end);
        BillingEngine engine = new BillingEngine(zone, tariffService::dayType,
                Duration.ofSeconds((long) (ctx.meter.getExpectedCadenceSeconds() * props.missingGapFactor())));
        Result result = engine.calculate(ctx.start, ctx.end, ctx.readings, ctx.tariffs);
        Basis basis = buildBasis(ctx, result);

        Bill bill = new Bill(UUID.randomUUID().toString(), ctx.meter.getId(), billingMonth,
                BillStatus.CONFIRMED, result.totalAmount(), result.totalKwh(), result.rawTotal(),
                result.roundingAmount(), basis.hash(), basis.snapshotJson(), Instant.now());
        billRepo.save(bill);
        persistDetails(bill, ctx, result);
        billRepo.flush();
        return assemblePersisted(billRepo.findById(bill.getId()).orElseThrow(),
                ctx.meterCode(), ctx.meterName());
    }

    private BillPreviewDto assemblePersisted(Bill bill, String meterCode, String meterName) {
        return Assembler.toPersisted(bill, meterCode, meterName,
                lineRepo.findByBillIdOrderByLineOrderAsc(bill.getId()),
                fragmentRepo.findByBillIdOrderBySegmentStartAsc(bill.getId()),
                allocRepo.findByBillId(bill.getId()),
                gapRepo.findByBillId(bill.getId()));
    }

    @Transactional(readOnly = true)
    public List<BillSummaryDto> listBills(String meterCode) {
        List<Bill> bills = meterCode == null
                ? billRepo.findAllByOrderByBillingMonthAsc()
                : billRepo.findByMeterIdOrderByBillingMonthDesc(
                        meterRepo.findByCode(meterCode).orElseThrow(() -> new BillingException("未知表计 " + meterCode)).getId());
        return bills.stream().map(b -> new BillSummaryDto(b.getId(), meterCodeOf(b.getMeterId()),
                b.getBillingMonth(), b.getStatus().name(),
                b.getTotalAmount().toPlainString(), b.getTotalKwh().toPlainString(),
                b.getRoundingAmount().toPlainString(), b.getConfirmedAt())).toList();
    }

    @Transactional(readOnly = true)
    public BillPreviewDto trace(String billId) {
        Bill bill = billRepo.findById(billId)
                .orElseThrow(() -> new BillingException("账单不存在 " + billId));
        Meter meter = meterRepo.findById(bill.getMeterId())
                .orElseThrow(() -> new BillingException("账单表计已丢失"));
        return assemblePersisted(bill, meter.getCode(), meter.getName());
    }

    // ----------------------------- 持久化 -----------------------------

    private void persistDetails(Bill bill, Context ctx, Result result) {
        Map<String, Tariff> tariffById = new HashMap<>();
        ctx.tariffs.forEach(t -> tariffById.put(t.id(), t));

        // 1) 费用行
        int order = 1;
        Map<String, BillLine> energyLineEntities = new HashMap<>();
        for (EnergyLine l : result.energyLines()) {
            Tariff t = tariffById.get(l.tariffId());
            BillLine entity = lineRepo.save(new BillLine(UUID.randomUUID().toString(), bill, LineKind.ENERGY,
                    l.periodType(), null, l.tariffId(), t == null ? null : t.code(),
                    l.kwh(), l.rawAmount(), l.amount(), order++));
            energyLineEntities.put(l.tariffId() + "#" + l.periodType().name(), entity);
        }
        Map<String, BillLine> tierLineEntities = new HashMap<>();
        for (TierLine l : result.tierLines()) {
            Tariff t = tariffById.get(l.tariffId());
            BillLine entity = lineRepo.save(new BillLine(UUID.randomUUID().toString(), bill, LineKind.TIER,
                    null, l.tierIndex(), l.tariffId(), t == null ? null : t.code(),
                    l.kwh(), l.rawAmount(), l.amount(), order++));
            tierLineEntities.put(l.tariffId() + "#" + l.tierIndex(), entity);
        }
        lineRepo.save(new BillLine(UUID.randomUUID().toString(), bill, LineKind.ROUNDING,
                null, null, null, null, BigDecimal.ZERO.setScale(9, RoundingMode.HALF_UP),
                result.roundingAmount(), result.roundingAmount(), order));
        lineRepo.flush();

        // 2) 电量片段（引用已落库的费用行）
        Map<Integer, Map<Integer, BillFragment>> fragIndex = new HashMap<>();
        int fi = 0;
        for (Fragment f : result.fragments()) {
            int si = 0;
            for (Segment s : f.segments()) {
                BillLine line = energyLineEntities.get(s.tariffId() + "#" + s.periodType().name());
                BillFragment frag = fragmentRepo.save(new BillFragment(UUID.randomUUID().toString(), bill,
                        line == null ? null : line.getId(),
                        f.intervalStart(), f.intervalEnd(), f.readingStart(), f.readingEnd(), f.intervalKwh(),
                        s.segmentStart(), s.segmentEnd(), s.share(), s.kwh(), s.periodType(), s.dayType(),
                        s.tariffId(), s.pricePerKwh()));
                fragIndex.computeIfAbsent(fi, k -> new HashMap<>()).put(si, frag);
                si++;
            }
            fi++;
        }
        fragmentRepo.flush();

        // 3) 阶梯分摊（引用已落库片段）
        for (TierAllocation a : result.tierAllocations()) {
            BillFragment frag = fragIndex.getOrDefault(a.fragmentIndex(), Map.of()).get(a.segmentIndex());
            if (frag == null) {
                throw new BillingException("阶梯分摊引用的片段丢失");
            }
            allocRepo.save(new BillTierAlloc(UUID.randomUUID().toString(), bill, frag.getId(),
                    a.tierIndex(), a.kwh(), a.cumulativeBefore(), a.surchargePerKwh()));
        }
        allocRepo.flush();

        // 4) 缺口
        for (Gap g : result.gaps()) {
            gapRepo.save(new BillGap(UUID.randomUUID().toString(), bill,
                    g.gapStart(), g.gapEnd(), GapReason.valueOf(g.reason()), g.detail()));
        }
        gapRepo.flush();
    }

    // ----------------------------- 依据指纹与快照 -----------------------------

    private Basis buildBasis(Context ctx, Result result) {
        LinkedHashMap<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("timezone", props.timezone());
        snapshot.put("billingMonth", ctx.ym.toString());
        snapshot.put("windowStartIso", ctx.start.toString());
        snapshot.put("windowEndIso", ctx.end.toString());
        snapshot.put("currency", props.billingCurrency());
        snapshot.put("meter", Map.of(
                "id", ctx.meter.getId(), "code", ctx.meter.getCode(), "name", ctx.meter.getName(),
                "expectedCadenceSeconds", ctx.meter.getExpectedCadenceSeconds()));

        List<Map<String, Object>> tariffList = new ArrayList<>();
        for (Tariff t : ctx.tariffs) {
            Map<String, Object> tm = new LinkedHashMap<>();
            tm.put("id", t.id());
            tm.put("code", t.code());
            tm.put("effectiveFrom", t.effectiveFrom().toString());
            tm.put("effectiveTo", t.effectiveTo() == null ? null : t.effectiveTo().toString());
            tm.put("tou", t.touRules().stream().map(r -> Map.of(
                    "type", r.periodType().name(),
                    "startMin", r.startMin(), "endMin", r.endMin(),
                    "price", r.pricePerKwh().stripTrailingZeros().toPlainString())).toList());
            tm.put("tiers", t.tiers().stream().map(b -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("index", b.tierIndex());
                m.put("lower", b.lowerKwh().stripTrailingZeros().toPlainString());
                m.put("upper", b.upperKwh() == null ? null : b.upperKwh().stripTrailingZeros().toPlainString());
                m.put("surcharge", b.surchargePerKwh().stripTrailingZeros().toPlainString());
                return m;
            }).toList());
            tariffList.add(tm);
        }
        snapshot.put("tariffs", tariffList);

        snapshot.put("readings", ctx.readings.stream().map(r -> r.ts() + "|" + r.readingKwh().stripTrailingZeros().toPlainString()).toList());

        List<Map<String, Object>> cal = new ArrayList<>();
        for (LocalDate d = LocalDate.of(ctx.ym.getYear(), ctx.ym.getMonth(), 1);
             !d.isAfter(ctx.ym.atEndOfMonth()); d = d.plusDays(1)) {
            cal.add(Map.of("date", d.toString(), "dayType", tariffService.dayType(d).name()));
        }
        snapshot.put("calendar", cal);

        snapshot.put("roundingRule", "HALF_UP");
        snapshot.put("moneyScale", BillingEngine.MONEY_SCALE);
        snapshot.put("kwhScale", BillingEngine.KWH_SCALE);
        snapshot.put("result", Map.of(
                "totalKwh", result.totalKwh().toPlainString(),
                "rawTotal", result.rawTotal().toPlainString(),
                "roundedLineSum", result.roundedLineSum().toPlainString(),
                "roundingAmount", result.roundingAmount().toPlainString(),
                "totalAmount", result.totalAmount().toPlainString()));

        String json;
        try {
            json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            throw new BillingException("序列化计费依据失败：" + e.getMessage());
        }
        String hash = sha256Hex(json);
        return new Basis(hash, json);
    }

    private record Basis(String hash, String snapshotJson) {}

    // ----------------------------- 上下文装载 -----------------------------

    private Context loadContext(String meterCode, String billingMonth) {
        Meter meter = meterRepo.findByCode(meterCode)
                .orElseThrow(() -> new BillingException("未知表计 " + meterCode));
        YearMonth ym = tariffService.ym(billingMonth);
        Instant start = tariffService.monthStart(ym);
        Instant end = tariffService.monthEnd(ym);

        // 为处理跨月边界的区间，多取账期前后各一条读数；窗口内区间由引擎剪裁
        List<MeterReading> around = readingRepo.findWindow(meter.getId(), start, end);
        MeterReading before = readingRepo.findLastAtOrBefore(meter.getId(), start).orElse(null);
        MeterReading after = readingRepo.findFirstAtOrAfter(meter.getId(), end).orElse(null);
        TreeMap<Instant, MeterReading> map = new TreeMap<>();
        if (before != null) map.put(before.getTs(), before);
        around.forEach(r -> map.put(r.getTs(), r));
        if (after != null) map.put(after.getTs(), after);

        List<RawReading> readings = map.values().stream()
                .map(r -> new RawReading(r.getTs(), r.getReadingKwh())).toList();
        List<Tariff> tariffs = tariffService.tariffsOverlapping(start, end);
        return new Context(meter, ym, start, end, readings, tariffs);
    }

    record Context(Meter meter, YearMonth ym, Instant start, Instant end,
                           List<RawReading> readings, List<Tariff> tariffs) {
        String meterCode() { return meter.getCode(); }
        String meterName() { return meter.getName(); }
        String billingMonth() { return ym.toString(); }
        String tariffCode(String id) {
            return tariffs.stream().filter(t -> t.id().equals(id)).map(Tariff::code).findFirst().orElse(null);
        }
    }

    private String meterCodeOf(String meterId) {
        return meterRepo.findById(meterId).map(Meter::getCode).orElse(meterId);
    }

    private static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
