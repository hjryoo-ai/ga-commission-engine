package ga.comm.calc.recipient;

import ga.comm.domain.event.PolicyEvent;
import ga.comm.domain.recipient.Recipient;
import ga.comm.domain.type.RecipientType;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 수급자 결정 — 설계사 본인 + 조직 오버라이드 체인 (설계서 §4.2 [2]).
 * 하나의 이벤트가 (설계사 1 + 조직 N) 개의 계산 단위를 낳는다.
 */
public class RecipientResolver {

    private final AgentDirectory directory;
    private final AffiliationBasis basis;

    public RecipientResolver(AgentDirectory directory, AffiliationBasis basis) {
        this.directory = Objects.requireNonNull(directory);
        this.basis = Objects.requireNonNull(basis);
    }

    public List<CalcTarget> resolve(PolicyEvent event) {
        LocalDate baseDate = switch (basis) {
            case EVENT_DATE -> event.eventDate();
            case CONTRACT_DATE -> event.contractDate();
        };

        String grade = directory.gradeOf(event.agentId(), baseDate)
                .orElseThrow(() -> new IllegalStateException(
                        "설계사 등급 이력이 없습니다: " + event.agentId() + " 기준일=" + baseDate));

        List<CalcTarget> targets = new ArrayList<>();
        targets.add(CalcTarget.agent(
                new Recipient(RecipientType.AGENT, event.agentId().value()), grade));

        for (OrgAssignment org : directory.orgChainOf(event.agentId(), baseDate)) {
            targets.add(CalcTarget.org(
                    new Recipient(RecipientType.ORG, org.orgId().value()), org.level()));
        }
        return targets;
    }
}
