package ga.comm.calc.contract;

import ga.comm.calc.recipient.AgentDirectory;
import ga.comm.calc.recipient.OrgAssignment;
import ga.comm.domain.id.AgentId;
import ga.comm.domain.id.OrgId;
import ga.comm.rule.model.EffectivePeriod;
import ga.comm.rule.model.OrgLevel;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AgentDirectory 계약 테스트 (설계서 §2.4, Phase 10b) — 등급/소속 이력의
 * <b>유효기간 경계 시맨틱 박제</b>: 변경일 당일에는 신 버전(등급·소속)이 적용된다.
 * 인메모리 레퍼런스와 Oracle 어댑터가 같은 답을 내야 한다.
 */
public abstract class AgentDirectoryContract {

    protected static final AgentId AGENT = new AgentId("A-1001");
    protected static final LocalDate APPOINTED = LocalDate.of(2025, 1, 1);
    protected static final LocalDate CHANGE_DATE = LocalDate.of(2026, 9, 1);

    protected abstract AgentDirectory directory();

    protected abstract void seedAgent(AgentId agentId, String name, LocalDate appointedOn);

    protected abstract void seedGrade(AgentId agentId, String gradeCd, EffectivePeriod period);

    /** 조직 마스터 등록 (parent는 상위 조직, HQ는 null). */
    protected abstract void seedOrg(OrgId orgId, OrgLevel level, OrgId parent);

    protected abstract void seedAssignment(AgentId agentId, OrgId orgId, EffectivePeriod period);

    protected <T> T inTx(Supplier<T> work) {
        return work.get();
    }

    @Test
    void 등급_변경일_당일에는_신_등급이_적용된다() {
        seedAgent(AGENT, "설계사A", APPOINTED);
        seedGrade(AGENT, "SR", EffectivePeriod.of(APPOINTED, CHANGE_DATE.minusDays(1)));
        seedGrade(AGENT, "EX", EffectivePeriod.from(CHANGE_DATE));

        assertThat(inTx(() -> directory().gradeOf(AGENT, CHANGE_DATE.minusDays(1)))).contains("SR");
        assertThat(inTx(() -> directory().gradeOf(AGENT, CHANGE_DATE))).contains("EX");
    }

    @Test
    void 위촉_전_기준일에는_등급이_없다() {
        seedAgent(AGENT, "설계사A", APPOINTED);
        seedGrade(AGENT, "SR", EffectivePeriod.from(APPOINTED));

        assertThat(inTx(() -> directory().gradeOf(AGENT, APPOINTED.minusDays(1)))).isEmpty();
        assertThat(inTx(() -> directory().gradeOf(AGENT, APPOINTED))).contains("SR");
    }

    @Test
    void 알_수_없는_설계사는_빈_결과다() {
        // 침묵 기본값이 아니라 empty — 필수 여부 판단·예외 승격은 호출측(RecipientResolver 등)의 몫
        AgentId unknown = new AgentId("A-9999");
        assertThat(inTx(() -> directory().gradeOf(unknown, LocalDate.of(2026, 8, 1)))).isEmpty();
        assertThat(inTx(() -> directory().orgChainOf(unknown, LocalDate.of(2026, 8, 1)))).isEmpty();
    }

    @Test
    void 소속_체인은_팀_지점_본부_순서로_반환된다() {
        seedAgent(AGENT, "설계사A", APPOINTED);
        OrgId hq = new OrgId("H1");
        OrgId branch = new OrgId("B1");
        OrgId team = new OrgId("T1");
        seedOrg(hq, OrgLevel.HQ, null);
        seedOrg(branch, OrgLevel.BRANCH, hq);
        seedOrg(team, OrgLevel.TEAM, branch);
        seedAssignment(AGENT, team, EffectivePeriod.from(APPOINTED));
        seedAssignment(AGENT, branch, EffectivePeriod.from(APPOINTED));
        seedAssignment(AGENT, hq, EffectivePeriod.from(APPOINTED));

        List<OrgAssignment> chain = inTx(() -> directory().orgChainOf(AGENT, LocalDate.of(2026, 8, 1)));

        assertThat(chain).containsExactly(
                new OrgAssignment(team, OrgLevel.TEAM),
                new OrgAssignment(branch, OrgLevel.BRANCH),
                new OrgAssignment(hq, OrgLevel.HQ));
    }

    @Test
    void 조직_이동일_당일에는_신_소속이_적용된다() {
        seedAgent(AGENT, "설계사A", APPOINTED);
        OrgId hq = new OrgId("H1");
        OrgId oldTeam = new OrgId("T1");
        OrgId newTeam = new OrgId("T2");
        seedOrg(hq, OrgLevel.HQ, null);
        seedOrg(oldTeam, OrgLevel.TEAM, hq);
        seedOrg(newTeam, OrgLevel.TEAM, hq);
        seedAssignment(AGENT, oldTeam, EffectivePeriod.of(APPOINTED, CHANGE_DATE.minusDays(1)));
        seedAssignment(AGENT, newTeam, EffectivePeriod.from(CHANGE_DATE));
        seedAssignment(AGENT, hq, EffectivePeriod.from(APPOINTED));

        List<OrgAssignment> before = inTx(() -> directory().orgChainOf(AGENT, CHANGE_DATE.minusDays(1)));
        List<OrgAssignment> after = inTx(() -> directory().orgChainOf(AGENT, CHANGE_DATE));

        assertThat(before).contains(new OrgAssignment(oldTeam, OrgLevel.TEAM))
                .doesNotContain(new OrgAssignment(newTeam, OrgLevel.TEAM));
        assertThat(after).contains(new OrgAssignment(newTeam, OrgLevel.TEAM))
                .doesNotContain(new OrgAssignment(oldTeam, OrgLevel.TEAM));
    }
}
