package ga.comm.infra.it;

import ga.comm.domain.money.Money;
import ga.comm.infra.OraclePersistence;
import ga.comm.rule.admin.IncentiveAdminStore;
import ga.comm.rule.contract.IncentiveApprovalContract;
import ga.comm.rule.model.EffectivePeriod;
import ga.comm.rule.model.IncentiveRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Oracle 어댑터가 시책 승인 워크플로 계약(트리밍/SUPERSEDE + diff 기반 변경 감사)을 만족함을 증명한다
 * (§6.6 v1.1.3 "다른 마스터 동일 적용"). 요율 승인 IT와 동일 구조 — 인메모리 레퍼런스와 같은 계약을
 * 실 Oracle에서 통과시키고, 변경 이력의 시각을 SQL로 직접 확인한다.
 */
class OracleIncentiveApprovalIT extends IncentiveApprovalContract {

    private final OraclePersistence persistence = OracleTestSupport.persistence();

    @BeforeEach
    void clean() {
        OracleTestSupport.cleanAll();
    }

    @Override
    protected IncentiveAdminStore adminStore() {
        return persistence.incentiveAdminStore();
    }

    @Override
    protected <T> T inTx(Supplier<T> work) {
        return persistence.inTx(work);
    }

    @Test
    void 변경_이력에는_시각과_승인자가_함께_기록된다() throws Exception {
        IncentiveRule draft = inTx(() -> service().registerDraftFixed("IT-PUSH", null, null, null,
                "premium >= 300000", Money.won(500_000), EffectivePeriod.from(LocalDate.of(2026, 1, 1))));
        inTx(() -> service().approve(draft.incentiveId(), "정산팀장A"));

        try (Connection c = OracleTestSupport.dataSource().getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT changed_at, changed_by FROM INCENTIVE_CHANGE_HIST WHERE incentive_id = "
                             + draft.incentiveId())) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getTimestamp(1)).as("변경 시각(§6.6 감사 요건)").isNotNull();
            assertThat(rs.getString(2)).isEqualTo("정산팀장A");
        }
    }
}
