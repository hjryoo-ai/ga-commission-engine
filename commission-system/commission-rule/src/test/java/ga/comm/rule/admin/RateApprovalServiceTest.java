package ga.comm.rule.admin;

import ga.comm.domain.id.CommTypeCode;
import ga.comm.domain.money.Rate;
import ga.comm.domain.type.Direction;
import ga.comm.rule.fixture.InMemoryRuleStore;
import ga.comm.rule.model.CommRateRule;
import ga.comm.rule.model.EffectivePeriod;
import ga.comm.rule.model.RateStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static ga.comm.rule.fixture.RuleFixtures.INSURER;
import static ga.comm.rule.fixture.RuleFixtures.PRODUCT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateApprovalServiceTest {

    private InMemoryRuleStore store;
    private RateApprovalService service;

    @BeforeEach
    void setUp() {
        store = new InMemoryRuleStore();
        service = new RateApprovalService(store);
    }

    private CommRateRule draftFrom(LocalDate from, String rateValue) {
        return service.registerDraft(Direction.OUTBOUND, INSURER, PRODUCT, CommTypeCode.FY_COMM,
                null, Rate.of(rateValue), EffectivePeriod.from(from));
    }

    @Test
    void 초안_등록은_버전을_증가시키고_조회에_노출되지_않는다() {
        CommRateRule d1 = draftFrom(LocalDate.of(2026, 1, 1), "7.0");
        CommRateRule d2 = draftFrom(LocalDate.of(2026, 9, 1), "6.5");

        assertThat(d1.versionNo()).isEqualTo(1);
        assertThat(d2.versionNo()).isEqualTo(2);
        assertThat(store.findRate(Direction.OUTBOUND, INSURER, PRODUCT, CommTypeCode.FY_COMM, null,
                LocalDate.of(2026, 6, 1))).isEmpty();
    }

    @Test
    void 승인하면_기존_열린_버전은_신규_개시일_전날로_트리밍된다() {
        CommRateRule v1 = service.approve(draftFrom(LocalDate.of(2026, 1, 1), "7.0").rateId());
        CommRateRule v2 = service.approve(draftFrom(LocalDate.of(2026, 9, 1), "6.5").rateId());

        CommRateRule trimmedV1 = store.findById(v1.rateId()).orElseThrow();
        assertThat(trimmedV1.status()).isEqualTo(RateStatus.ACTIVE);
        assertThat(trimmedV1.period().applyTo()).isEqualTo(LocalDate.of(2026, 8, 31));

        // 경계일 조회: 8/31 구버전, 9/1 신버전
        assertThat(store.findRate(Direction.OUTBOUND, INSURER, PRODUCT, CommTypeCode.FY_COMM, null,
                LocalDate.of(2026, 8, 31)).orElseThrow().rateId()).isEqualTo(v1.rateId());
        assertThat(store.findRate(Direction.OUTBOUND, INSURER, PRODUCT, CommTypeCode.FY_COMM, null,
                LocalDate.of(2026, 9, 1)).orElseThrow().rateId()).isEqualTo(v2.rateId());
    }

    @Test
    void 완전히_덮이는_기존_버전은_SUPERSEDED_된다() {
        CommRateRule v1 = service.approve(draftFrom(LocalDate.of(2026, 9, 1), "6.5").rateId());
        // 소급 정정: 같은 시점부터 새 요율
        CommRateRule v2 = service.approve(draftFrom(LocalDate.of(2026, 9, 1), "6.8").rateId());

        assertThat(store.findById(v1.rateId()).orElseThrow().status()).isEqualTo(RateStatus.SUPERSEDED);
        assertThat(store.findRate(Direction.OUTBOUND, INSURER, PRODUCT, CommTypeCode.FY_COMM, null,
                LocalDate.of(2026, 10, 1)).orElseThrow().rateId()).isEqualTo(v2.rateId());
    }

    @Test
    void 기존_버전을_분할하는_승인은_거부된다() {
        service.approve(draftFrom(LocalDate.of(2026, 1, 1), "7.0").rateId());
        // 중간 구간만 바꾸려는 시도 (2026-05-01 ~ 2026-06-30)
        CommRateRule splitting = service.registerDraft(Direction.OUTBOUND, INSURER, PRODUCT,
                CommTypeCode.FY_COMM, null, Rate.of("5.0"),
                EffectivePeriod.of(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 6, 30)));

        assertThatThrownBy(() -> service.approve(splitting.rateId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("분할");
    }

    @Test
    void DRAFT가_아니면_승인_폐기_불가() {
        CommRateRule approved = service.approve(draftFrom(LocalDate.of(2026, 1, 1), "7.0").rateId());
        assertThatThrownBy(() -> service.approve(approved.rateId()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.discardDraft(approved.rateId()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 운영_승인은_실명이어야_한다_blank_system_거부() {
        long id = draftFrom(LocalDate.of(2026, 1, 1), "7.0").rateId();
        // 운영 경로(2-arg, 컨트롤러/러너/배치): §6.6 실명 요건을 서비스 계층이 강제 — 심층 방어
        assertThatThrownBy(() -> service.approve(id, "system"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("실명");
        assertThatThrownBy(() -> service.approve(id, "System"))  // 대소문자 무관(기술 계정 방어)
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.approve(id, "  "))
                .isInstanceOf(IllegalArgumentException.class);
        // 실명은 승인된다
        assertThat(service.approve(id, "김승인").status()).isEqualTo(RateStatus.ACTIVE);
    }

    @Test
    void 시드_대역_1arg_승인은_system을_허용한다() {
        // 인자 없는 오버로드 = 시드/테스트 대역 — "system" 기록이 허용된다(운영 검증의 예외 경로)
        long id = draftFrom(LocalDate.of(2026, 1, 1), "7.0").rateId();
        assertThat(service.approve(id).status()).isEqualTo(RateStatus.ACTIVE);
    }

    @Test
    void 승인_후에도_겹치는_ACTIVE는_존재하지_않는다() {
        service.approve(draftFrom(LocalDate.of(2026, 1, 1), "7.0").rateId());
        service.approve(draftFrom(LocalDate.of(2026, 9, 1), "6.5").rateId());
        service.approve(draftFrom(LocalDate.of(2027, 1, 1), "6.0").rateId());

        // 임의 기준일 조회가 항상 단일 버전을 반환 (Ambiguous 미발생)
        for (LocalDate d : new LocalDate[]{
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 8, 31), LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 12, 31), LocalDate.of(2027, 1, 1), LocalDate.of(2030, 1, 1)}) {
            assertThat(store.findRate(Direction.OUTBOUND, INSURER, PRODUCT, CommTypeCode.FY_COMM, null, d))
                    .isPresent();
        }
    }
}
