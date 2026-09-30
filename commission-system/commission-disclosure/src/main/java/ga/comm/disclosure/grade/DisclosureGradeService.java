package ga.comm.disclosure.grade;

import ga.comm.disclosure.grade.group.GroupMember;
import ga.comm.disclosure.grade.group.ProductGroupDirectory;
import ga.comm.disclosure.grade.measure.Measure;
import ga.comm.disclosure.grade.measure.MeasureOutcome;
import ga.comm.disclosure.grade.measure.MeasureRegistry;
import ga.comm.disclosure.grade.policy.GradeBand;
import ga.comm.disclosure.grade.policy.GradingPolicySpec;
import ga.comm.disclosure.grade.policy.InvalidPolicyException;
import ga.comm.disclosure.grade.policy.PolicySource;
import ga.comm.disclosure.grade.policy.PolicyVersion;
import ga.comm.disclosure.grade.policy.RankingPolicySpec;
import ga.comm.disclosure.grade.policy.UnavailableCause;
import ga.comm.disclosure.grade.rank.SetRanker;
import ga.comm.disclosure.grade.snapshot.GradeSnapshot;
import ga.comm.disclosure.grade.snapshot.GradeSnapshotStore;
import ga.comm.disclosure.grade.snapshot.SnapshotIntegrityException;
import ga.comm.disclosure.grade.snapshot.SnapshotJson;
import ga.comm.disclosure.grade.snapshot.SnapshotNotFoundException;
import ga.comm.disclosure.grade.snapshot.StoredSnapshot;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 비교설명 등급·순위 산출(ga-disclosure 계약 {@code POST /internal/v1/disclosure/commission-grades})과 재조회.
 *
 * <p>순서: 테넌트(403) → 정책 해석(Ambiguous 409 / 0건 422 / 결함 422) → 기준일 미래(400, 정책 파라미터) → 상품군 코드 체계(400)
 * → 측정 기간 → 모집단 측정 → 요청 항목 판정(OK 후보 / UNAVAILABLE 원인) → 세트 순위 → 비율(1회 반올림)·등급 → 자기 검증(422)
 * → 트랜잭션 안에서 채번(SEQUENCE, E3.1)·정규화·해시·저장. 응답 본문은 저장한 정규 문자열 그대로다.
 *
 * <p>임계치·라벨·사유 코드·동점 규칙은 정책 데이터에서만 온다. 이 클래스에는 그런 값이 없다.
 */
public final class DisclosureGradeService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

    private final PolicySource policies;
    private final ProductGroupDirectory groups;
    private final MeasureRegistry measures;
    private final GradeSnapshotStore snapshots;
    private final Transactions transactions;
    private final Clock clock;
    private final String instanceTenantId;

    public DisclosureGradeService(PolicySource policies, ProductGroupDirectory groups, MeasureRegistry measures,
                                  GradeSnapshotStore snapshots, Transactions transactions, Clock clock,
                                  String instanceTenantId) {
        this.policies = Objects.requireNonNull(policies);
        this.groups = Objects.requireNonNull(groups);
        this.measures = Objects.requireNonNull(measures);
        this.snapshots = Objects.requireNonNull(snapshots);
        this.transactions = Objects.requireNonNull(transactions);
        this.clock = Objects.requireNonNull(clock);
        if (instanceTenantId == null || instanceTenantId.isBlank()) {
            throw new IllegalStateException("engine instance tenant id is required (app.tenant-id) — no default");
        }
        this.instanceTenantId = instanceTenantId;
    }

    /** 발급된 스냅샷 ID와 정규 응답 문자열. */
    public record Issued(String snapshotId, String responseCanonical) {
    }

    public Issued issue(GradeRequest request) {
        if (!instanceTenantId.equals(request.tenantId())) {
            throw new TenantMismatchException(request.tenantId());
        }
        LocalDate asOf = request.asOfDate();
        PolicyVersion<GradingPolicySpec> grading = policies.grading(asOf);
        PolicyVersion<RankingPolicySpec> ranking = policies.ranking(asOf);
        GradingPolicySpec spec = grading.spec();

        LocalDate today = LocalDate.now(clock);
        if (asOf.isAfter(today.plusDays(spec.asOfFutureDaysAllowed()))) {
            throw new GradeRequestException("AS_OF_IN_FUTURE", "asOfDate " + asOf + " is after today " + today + " + "
                    + spec.asOfFutureDaysAllowed() + " day(s) allowed by " + grading.id());
        }
        if (!groups.groupExists(spec.groupCodeSystem(), request.productGroupCode(), asOf)) {
            throw new GradeRequestException("UNKNOWN_PRODUCT_GROUP", "productGroupCode " + request.productGroupCode()
                    + " is not in code system " + spec.groupCodeSystem() + " on " + asOf);
        }
        Measure measure = measures.get(spec.measureKey());
        List<String> paramProblems = measure.validateParams(spec.measureParams());
        if (!paramProblems.isEmpty()) {
            throw new InvalidPolicyException(paramProblems);
        }

        MeasurePeriod period = MeasurePeriod.resolve(spec.periodKind(), spec.asOfRule(), asOf);
        Map<String, MeasureOutcome> byMember = new LinkedHashMap<>();
        for (GroupMember member : groups.members(spec.groupCodeSystem(), request.productGroupCode(), period.measureDate())) {
            if (byMember.putIfAbsent(member.extProductKey(), measure.measure(member, period.measureDate(), spec.measureParams()))
                    != null) {
                throw new InvalidPolicyException(List.of("product " + member.extProductKey() + " appears twice in group "
                        + request.productGroupCode() + " on " + period.measureDate()));
            }
        }
        List<BigDecimal> population = byMember.values().stream()
                .filter(MeasureOutcome.Value.class::isInstance).map(o -> ((MeasureOutcome.Value) o).value()).toList();
        BigDecimal sum = population.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean insufficient = population.size() < spec.minPopulation() || sum.signum() == 0;

        // 요청 항목 판정 — 요청 순서 유지
        Map<String, UnavailableCause> unavailable = new LinkedHashMap<>();
        List<SetRanker.Candidate> candidates = new ArrayList<>();
        for (GradeRequest.Product p : request.products()) {
            MeasureOutcome outcome = byMember.get(p.productKey());
            if (insufficient) {
                unavailable.put(p.productKey(), UnavailableCause.INSUFFICIENT_POPULATION);
            } else if (outcome == null) {
                unavailable.put(p.productKey(), UnavailableCause.NOT_IN_GROUP);
            } else if (outcome instanceof MeasureOutcome.Missing missing) {
                unavailable.put(p.productKey(), missing.cause());
            } else {
                candidates.add(new SetRanker.Candidate(p.productKey(), ((MeasureOutcome.Value) outcome).value()));
            }
        }

        List<GradeResult> results = new ArrayList<>();
        for (SetRanker.Ranked r : SetRanker.rank(candidates, ranking.spec())) {
            RatioToAvg ratio = RatioToAvg.of(r.measure(), population.size(), sum, spec.ratioScale(), spec.ratioRounding());
            GradeBand band = spec.bandOf(ratio.value());
            results.add(new GradeResult.Ok(r.productKey(), ratio.text(), band.code(), band.label(), band.ordinal(), r.rank(),
                    r.tie()));
        }
        unavailable.forEach((key, cause) -> results.add(new GradeResult.Unavailable(key, spec.reasonFor(cause))));
        SelfCheck.verify(results, ranking.spec().tieBreak());

        OffsetDateTime generatedAt = OffsetDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
        GradeSnapshot.Basis basis = new GradeSnapshot.Basis(measure.groupAvgSource(), period.label(), population.size());
        return transactions.inTx(() -> {
            // 번호는 날마다 다시 세지 않는다(E3.1 §3-4). 대역 밖 값은 조용히 쓰지 않는다(B-13).
            long number = snapshots.nextNumber();
            if (number < GradeSnapshotStore.FIRST_NUMBER || number > GradeSnapshotStore.LAST_NUMBER) {
                throw new IllegalStateException("snapshot number out of range: " + number);
            }
            String snapshotId = "GRD-" + generatedAt.toLocalDate().format(DAY) + "-" + number;
            GradeSnapshot snapshot = new GradeSnapshot(snapshotId, request.tenantId(), asOf, request.productGroupCode(),
                    grading.id(), ranking.id(), ranking.spec().tieBreak(), basis, results, generatedAt);
            String canonical = SnapshotJson.canonical(snapshot);
            snapshots.save(snapshot, canonical, SnapshotJson.sha256Hex(canonical));
            return new Issued(snapshotId, canonical);
        });
    }

    /** 저장된 정규 응답을 그대로 돌려준다 — 해시를 대조한 뒤. 재직렬화하지 않는다. */
    public String refetch(String snapshotId) {
        StoredSnapshot stored = snapshots.find(snapshotId)
                .filter(s -> s.tenantId().equals(instanceTenantId))
                .orElseThrow(() -> new SnapshotNotFoundException(snapshotId));
        byte[] recomputed = SnapshotJson.sha256Hex(stored.responseCanonical()).getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(recomputed, stored.responseSha256().getBytes(StandardCharsets.US_ASCII))) {
            throw new SnapshotIntegrityException(snapshotId);
        }
        return stored.responseCanonical();
    }
}
