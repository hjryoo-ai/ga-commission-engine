package ga.comm.shadow;

import ga.comm.calc.net.NetAmountCalculator;
import ga.comm.calc.store.CommCalcRecord;
import ga.comm.calc.store.CommCalcStore;
import ga.comm.domain.id.CommTypeCode;
import ga.comm.domain.money.Money;
import ga.comm.domain.time.CloseYm;
import ga.comm.domain.type.RecipientType;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 섀도 런 (설계서 §8.5) — 외부 정산 결과와 자체 계산을 <b>수급자×유형×마감월</b> 원 단위로 대조하고
 * 차이를 유형화한다(§7 대사 패턴 재사용·확장). 병행 계산 1~2주기 후 전환 판단에 쓴다.
 *
 * <p><b>자체 순액은 전부 {@link NetAmountCalculator}</b>(전 상태 합산, §3.0)로 산출한다 — 상태 필터
 * SUM 금지. 허용 오차는 기본 0원(일치만 clean)이며, 반올림 허용폭({@code roundingBound})은 미세 차를
 * <b>숨기지 않고</b> ROUNDING으로 <b>분류</b>만 한다(전수 보고).
 *
 * <p>골든셋 러너와 달리 실 스토어 경로다 — {@link CommCalcStore}는 인메모리든 Oracle이든 동일하게 동작한다.
 */
public class ShadowRunService {

    private final CommCalcStore calcStore;
    private final NetAmountCalculator net;

    public ShadowRunService(CommCalcStore calcStore) {
        this.calcStore = Objects.requireNonNull(calcStore);
        this.net = new NetAmountCalculator(calcStore);
    }

    public record ShadowDiff(RecipientType recipientType, String recipientId, CommTypeCode commType,
                             CloseYm closeYm, Money internal, Money external, Money diff,
                             ShadowDiffType type) {
    }

    public record ShadowReport(List<ShadowDiff> diffs) {
        public List<ShadowDiff> byType(ShadowDiffType type) {
            return diffs.stream().filter(d -> d.type() == type).toList();
        }

        /** 허용 오차 0원 — 전부 MATCH여야 clean. */
        public boolean clean() {
            return diffs.stream().allMatch(d -> d.type() == ShadowDiffType.MATCH);
        }

        /** 유형별 건수 요약(빌드/리포트 출력용). */
        public Map<ShadowDiffType, Integer> summary() {
            Map<ShadowDiffType, Integer> counts = new EnumMap<>(ShadowDiffType.class);
            for (ShadowDiff d : diffs) {
                counts.merge(d.type(), 1, Integer::sum);
            }
            return counts;
        }
    }

    /** 대조 — 자체 계산 vs 외부 정산. {@code roundingBound} 이내의 차는 ROUNDING으로 분류(0이면 미분류). */
    public ShadowReport compare(List<ExternalSettlementRow> external, List<CloseYm> periods,
                                long roundingBound) {
        Set<Key> keys = new LinkedHashSet<>();
        for (CloseYm period : periods) {
            for (CommCalcRecord r : calcStore.findByCloseYm(period)) {
                keys.add(new Key(r.recipientType(), r.recipientId(), r.commType(), period));
            }
        }
        Map<Key, Money> externalByKey = new LinkedHashMap<>();
        for (ExternalSettlementRow row : external) {
            Key key = new Key(row.recipientType(), row.recipientId(), row.commType(), row.closeYm());
            externalByKey.merge(key, row.amount(), Money::plus);
            keys.add(key);
        }

        List<ShadowDiff> diffs = new ArrayList<>();
        for (Key key : keys) {
            Money internal = net.netOf(key.recipientType, key.recipientId, key.commType, key.closeYm);
            Money ext = externalByKey.getOrDefault(key, Money.ZERO);
            diffs.add(classify(key, internal, ext, roundingBound));
        }
        reclassifyTiming(diffs);
        return new ShadowReport(List.copyOf(diffs));
    }

    private static ShadowDiff classify(Key key, Money internal, Money external, long roundingBound) {
        Money diff = internal.minus(external);
        ShadowDiffType type;
        if (diff.isZero()) {
            type = ShadowDiffType.MATCH;
        } else if (external.isZero()) {
            type = ShadowDiffType.UNEXPECTED;      // 자체만 존재
        } else if (internal.isZero()) {
            type = ShadowDiffType.MISSING;         // 외부만 존재
        } else if (Math.abs(diff.toLong()) <= roundingBound) {
            type = ShadowDiffType.ROUNDING;        // 양쪽 존재, 미세 차
        } else {
            type = ShadowDiffType.RATE_DIFF;       // 양쪽 존재, 큰 차
        }
        return new ShadowDiff(key.recipientType, key.recipientId, key.commType, key.closeYm,
                internal, external, diff, type);
    }

    /**
     * 타이밍 재분류 — 같은 (수급자×유형) 금액이 한 마감월엔 MISSING(외부만), 다른 마감월엔
     * UNEXPECTED(자체만)로 나뉘고 두 금액이 상쇄하면 귀속 타이밍 차이다. 둘 다 TIMING으로 바꾼다.
     */
    private static void reclassifyTiming(List<ShadowDiff> diffs) {
        for (int i = 0; i < diffs.size(); i++) {
            ShadowDiff a = diffs.get(i);
            if (a.type() != ShadowDiffType.MISSING) {
                continue;
            }
            for (int j = 0; j < diffs.size(); j++) {
                ShadowDiff b = diffs.get(j);
                if (b.type() == ShadowDiffType.UNEXPECTED
                        && b.recipientType() == a.recipientType()
                        && b.recipientId().equals(a.recipientId())
                        && b.commType().equals(a.commType())
                        && b.internal().equals(a.external())) {
                    diffs.set(i, withType(a, ShadowDiffType.TIMING));
                    diffs.set(j, withType(b, ShadowDiffType.TIMING));
                    break;
                }
            }
        }
    }

    private static ShadowDiff withType(ShadowDiff d, ShadowDiffType type) {
        return new ShadowDiff(d.recipientType(), d.recipientId(), d.commType(), d.closeYm(),
                d.internal(), d.external(), d.diff(), type);
    }

    private record Key(RecipientType recipientType, String recipientId, CommTypeCode commType,
                       CloseYm closeYm) {
    }
}
