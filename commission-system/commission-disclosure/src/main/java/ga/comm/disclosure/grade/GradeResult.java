package ga.comm.disclosure.grade;

import java.util.Objects;

/**
 * 계약 results[] 한 항목 — status로 분기하는 oneOf. UNAVAILABLE은 등급·순위·비율 6개 필드를 <b>갖지 않는다</b>(null이 아니라 부재):
 * 두 타입으로 나눠 필드 자체가 없으므로 직렬화에 null이 나올 수 없다.
 */
public sealed interface GradeResult {

    String productKey();

    record Ok(String productKey, String ratioToAvg, String grade, String gradeLabel, int gradeOrdinal, int rankInSet,
              boolean tie) implements GradeResult {
        public Ok {
            Objects.requireNonNull(productKey, "productKey");
            Objects.requireNonNull(ratioToAvg, "ratioToAvg");
            Objects.requireNonNull(grade, "grade");
            Objects.requireNonNull(gradeLabel, "gradeLabel");
        }
    }

    record Unavailable(String productKey, String reason) implements GradeResult {
        public Unavailable {
            Objects.requireNonNull(productKey, "productKey");
            Objects.requireNonNull(reason, "reason");
        }
    }
}
