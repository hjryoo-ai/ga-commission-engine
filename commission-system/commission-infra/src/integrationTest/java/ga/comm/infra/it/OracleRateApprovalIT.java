package ga.comm.infra.it;

import ga.comm.infra.OraclePersistence;
import ga.comm.rule.admin.CommRateAdminStore;
import ga.comm.rule.contract.RateApprovalContract;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Oracle 어댑터가 승인 워크플로 계약(트리밍/SUPERSEDE + 변경 감사)을 만족함을 증명한다.
 * 추가로 COMM_RATE_CHANGE_HIST의 시각(changed_at) 기록을 SQL로 직접 검증한다 (§6.6).
 */
class OracleRateApprovalIT extends RateApprovalContract {

    private final OraclePersistence persistence = OracleTestSupport.persistence();

    @BeforeEach
    void clean() {
        OracleTestSupport.cleanAll();
    }

    @Override
    protected CommRateAdminStore adminStore() {
        return persistence.commRateAdminStore();
    }

    @Override
    protected <T> T inTx(Supplier<T> work) {
        return persistence.inTx(work);
    }

    @Test
    void 변경_이력에는_시각이_함께_기록된다() throws Exception {
        var draft = inTx(() -> service().registerDraft(
                ga.comm.domain.type.Direction.OUTBOUND, new ga.comm.domain.id.InsurerCode("SAMLIFE"),
                new ga.comm.domain.id.ProductKey("WL-20Y"), ga.comm.domain.id.CommTypeCode.FY_COMM,
                null, ga.comm.domain.money.Rate.of("7.0"),
                ga.comm.rule.model.EffectivePeriod.from(RULES_FROM)));
        inTx(() -> service().approve(draft.rateId(), "정산팀장A"));

        try (Connection c = OracleTestSupport.dataSource().getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT changed_at, changed_by FROM COMM_RATE_CHANGE_HIST WHERE rate_id = "
                             + draft.rateId())) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getTimestamp(1)).as("변경 시각(§6.6 감사 요건)").isNotNull();
            assertThat(rs.getString(2)).isEqualTo("정산팀장A");
        }
    }
}
