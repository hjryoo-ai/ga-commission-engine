package ga.comm.disclosure.grade.measure;

import com.fasterxml.jackson.databind.JsonNode;
import ga.comm.disclosure.grade.group.GroupMember;

import java.time.LocalDate;
import java.util.List;

/**
 * 등급의 모수(measure) 함수. 어떤 수치를 "판매수수료 수준"으로 볼지는 외부 확정 사실(설계서 §11 #13)이므로 교체 가능하다 —
 * 등급 정책 데이터의 {@code measureKey}가 {@link MeasureRegistry}에서 함수를 고른다(엔진 Step 명시 목록과 같은 패턴).
 */
public interface Measure {

    /** 레지스트리 키(함수의 정체 — Step의 stepId와 같은 역할). */
    String key();

    /** 계약 basis.groupAvgSource 값(ASSOC_DISCLOSURE | ENGINE_LEDGER). */
    String groupAvgSource();

    /** 정책의 measureParams 검증. 위반 목록(빈 목록이면 통과). */
    List<String> validateParams(JsonNode params);

    MeasureOutcome measure(GroupMember member, LocalDate measureDate, JsonNode params);
}
