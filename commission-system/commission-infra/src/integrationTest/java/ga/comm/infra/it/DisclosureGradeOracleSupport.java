package ga.comm.infra.it;

import ga.comm.disclosure.grade.DisclosureGradeService;
import ga.comm.disclosure.grade.measure.FySalesCommissionRateMeasure;
import ga.comm.disclosure.grade.measure.MeasureRegistry;
import ga.comm.disclosure.grade.policy.PolicyResolver;
import ga.comm.infra.OraclePersistence;

import java.time.Clock;
import java.util.List;

/** Oracle 어댑터로 조립한 등급 서비스(운영 배선과 같은 구성, 트랜잭션 = OraclePersistence.inTx). */
final class DisclosureGradeOracleSupport {

    private DisclosureGradeOracleSupport() {
    }

    static DisclosureGradeService service(OraclePersistence p, Clock clock, String tenant) {
        return new DisclosureGradeService(new PolicyResolver(p.disclosurePolicyRepository()), p.productGroupDirectory(),
                MeasureRegistry.of(List.of(new FySalesCommissionRateMeasure(p.salesRateLedger()))), p.gradeSnapshotStore(),
                p::inTx, clock, tenant);
    }
}
