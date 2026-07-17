package ga.comm.calc;

/** 계산에 사용한 룰 버전 참조 — COMM_CALC.rule_versions에 박제되는 단위. */
public record RuleVersionRef(String ruleType, String key, String versionRef) {
}
