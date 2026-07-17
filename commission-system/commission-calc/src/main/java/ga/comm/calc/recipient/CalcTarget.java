package ga.comm.calc.recipient;

import ga.comm.domain.recipient.Recipient;
import ga.comm.rule.model.OrgLevel;

/**
 * 수급자 결정 결과 — (이벤트 × 수급자) 계산 단위 하나.
 *
 * @param recipient 수급자
 * @param gradeCd   설계사 수급자의 등급 (지급률 결정, ORG면 null)
 * @param orgLevel  조직 수급자의 계층 (오버라이드율 결정, AGENT면 null)
 */
public record CalcTarget(Recipient recipient, String gradeCd, OrgLevel orgLevel) {

    public static CalcTarget agent(Recipient recipient, String gradeCd) {
        return new CalcTarget(recipient, gradeCd, null);
    }

    public static CalcTarget org(Recipient recipient, OrgLevel level) {
        return new CalcTarget(recipient, null, level);
    }

    public boolean isAgent() {
        return recipient.isAgent();
    }
}
