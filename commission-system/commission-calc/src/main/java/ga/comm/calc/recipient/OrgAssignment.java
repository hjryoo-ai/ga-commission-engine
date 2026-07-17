package ga.comm.calc.recipient;

import ga.comm.domain.id.OrgId;
import ga.comm.rule.model.OrgLevel;

/** 설계사의 소속 조직 (기준일 시점). */
public record OrgAssignment(OrgId orgId, OrgLevel level) {
}
