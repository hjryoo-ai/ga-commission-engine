package ga.comm.app.config;

import ga.comm.app.security.InstanceProperties;
import ga.comm.disclosure.grade.DisclosureGradeService;
import ga.comm.disclosure.grade.measure.FySalesCommissionRateMeasure;
import ga.comm.disclosure.grade.measure.MeasureRegistry;
import ga.comm.disclosure.grade.policy.PolicyResolver;
import ga.comm.infra.OraclePersistence;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;
import java.util.List;

/**
 * 비교설명 등급·순위 배선(Phase E3). 측정 함수는 명시 목록으로 등록한다(CalcPipelineConfig의 Step 명시 목록과 같은 패턴) —
 * 등급 정책 데이터의 measureKey가 이 레지스트리에서 함수를 고른다. 시계는 Asia/Seoul 고정(스냅샷 ID 날짜·미래일 판정의 결정론).
 */
@Configuration(proxyBeanMethods = false)
public class DisclosureGradeConfig {

    @Bean
    public Clock clock() {
        return Clock.system(ZoneId.of("Asia/Seoul"));
    }

    @Bean
    public MeasureRegistry measureRegistry(OraclePersistence p) {
        return MeasureRegistry.of(List.of(new FySalesCommissionRateMeasure(p.salesRateLedger())));
    }

    @Bean
    public DisclosureGradeService disclosureGradeService(OraclePersistence p, MeasureRegistry measures, Clock clock,
                                                         InstanceProperties instance) {
        return new DisclosureGradeService(new PolicyResolver(p.disclosurePolicyRepository()), p.productGroupDirectory(), measures,
                p.gradeSnapshotStore(), p::inTx, clock, instance.requiredTenantId());
    }
}
