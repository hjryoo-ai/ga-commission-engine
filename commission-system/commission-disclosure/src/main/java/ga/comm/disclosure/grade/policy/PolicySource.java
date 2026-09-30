package ga.comm.disclosure.grade.policy;

import java.time.LocalDate;

/** 기준일로 해석된 등급·순위 정책. 운영 구현은 {@link PolicyResolver}(저장소 + 로드 시 검증). */
public interface PolicySource {

    PolicyVersion<GradingPolicySpec> grading(LocalDate asOf);

    PolicyVersion<RankingPolicySpec> ranking(LocalDate asOf);
}
