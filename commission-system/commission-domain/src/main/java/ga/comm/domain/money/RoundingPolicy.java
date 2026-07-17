package ga.comm.domain.money;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 중앙화된 반올림 정책.
 *
 * <p>코드 전체에서 이 enum 외에는 {@link RoundingMode}를 직접 사용하지 않는다.
 * 어떤 수수료 유형에 어떤 정책을 쓰는지는 COMM_TYPE 마스터 속성(데이터)으로 결정한다.
 *
 * <p>주의: {@code KRW_FLOOR}는 음수에 대해 -100.5 → -101 로 동작한다(FLOOR = 음의 무한 방향).
 * 환수 금액은 양수 기준액으로 계산·절사한 뒤 부호를 반전하는 것을 규약으로 한다.
 */
public enum RoundingPolicy {

    /** 원 단위 이하 절사 (기본값, 사규 확인 필요) */
    KRW_FLOOR(0, RoundingMode.FLOOR),

    /** 원 단위 반올림 */
    KRW_HALF_UP(0, RoundingMode.HALF_UP);

    private final int scale;
    private final RoundingMode mode;

    RoundingPolicy(int scale, RoundingMode mode) {
        this.scale = scale;
        this.mode = mode;
    }

    public BigDecimal apply(BigDecimal raw) {
        return raw.setScale(scale, mode);
    }
}
