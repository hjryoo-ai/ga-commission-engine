package ga.comm.disclosure.grade.policy;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * 등급 정책 body(버전 레코드 {@code DISC_GRADING_POLICY.body})의 해석 결과. 검증은 {@link PolicyLoader}가 로드 시 한다
 * (겹침·빈틈·ordinal 중복·라벨 누락 → 실패). 임계치·라벨·사유 코드는 여기(데이터)에만 있다.
 *
 * @param measureKey           측정 함수 레지스트리 키
 * @param measureParams        측정 함수 파라미터(함수가 스스로 검증)
 * @param groupCodeSystem      상품군 코드 체계(요청 productGroupCode가 이 체계에 없으면 400)
 * @param minPopulation        모집단 최소 크기(미만이면 상품군 전체 INSUFFICIENT_POPULATION)
 * @param asOfFutureDaysAllowed 기준일이 오늘보다 며칠 뒤까지 허용되는가(0 = 미래일 거부)
 * @param ratioScale           ratioToAvg 소수 자릿수
 * @param ratioRounding        ratioToAvg 반올림 모드(1회만 적용)
 * @param unavailableReasons   산출 불가 원인 → 응답 사유 코드
 */
public record GradingPolicySpec(String measureKey, JsonNode measureParams, String groupCodeSystem,
                                PopulationScope populationScope, int minPopulation,
                                PeriodKind periodKind, AsOfRule asOfRule, int asOfFutureDaysAllowed,
                                int ratioScale, RoundingMode ratioRounding,
                                List<GradeBand> grades, Map<UnavailableCause, String> unavailableReasons) {

    public GradingPolicySpec {
        grades = List.copyOf(grades);
        unavailableReasons = Map.copyOf(unavailableReasons);
    }

    /** 반올림된 비율이 속하는 등급. 로드 검증을 통과한 정책이면 정확히 1개다. */
    public GradeBand bandOf(BigDecimal ratio) {
        List<GradeBand> hits = grades.stream().filter(b -> b.contains(ratio)).toList();
        if (hits.size() != 1) {
            throw new InvalidPolicyException(List.of("ratio " + ratio.toPlainString() + " matches " + hits.size() + " grades"));
        }
        return hits.getFirst();
    }

    /** 원인에 대한 응답 사유 코드. 정책이 그 원인을 매핑하지 않았으면 정책 데이터 결함(fail-fast). */
    public String reasonFor(UnavailableCause cause) {
        String reason = unavailableReasons.get(cause);
        if (reason == null) {
            throw new InvalidPolicyException(List.of("unavailableReasons has no code for " + cause));
        }
        return reason;
    }
}
