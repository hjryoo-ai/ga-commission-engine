package ga.comm.rule.model;

import ga.comm.domain.money.Rate;
import ga.comm.domain.type.EventType;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 환수율 테이블 (CLAWBACK_RULE의 구간 집합). 기준일은 계약 체결일.
 *
 * @param productKey null = 전 상품 공통
 */
public record ClawbackTable(
        String productKey,
        EventType eventType,
        EffectivePeriod period,
        List<Band> bands
) {
    /** 경과 회차 구간 [fromInstallment, toInstallment] (양끝 포함)별 환수율. */
    public record Band(int fromInstallment, int toInstallment, Rate clawbackPct) {
        public Band {
            if (fromInstallment < 0 || toInstallment < fromInstallment) {
                throw new IllegalArgumentException(
                        "환수 구간이 올바르지 않습니다: " + fromInstallment + "~" + toInstallment);
            }
            Objects.requireNonNull(clawbackPct, "clawbackPct");
        }

        public boolean covers(int elapsedInstallments) {
            return elapsedInstallments >= fromInstallment && elapsedInstallments <= toInstallment;
        }
    }

    public ClawbackTable {
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(bands, "bands");
        bands = List.copyOf(bands);
        for (int i = 0; i < bands.size(); i++) {
            for (int j = i + 1; j < bands.size(); j++) {
                Band a = bands.get(i);
                Band b = bands.get(j);
                if (a.fromInstallment() <= b.toInstallment() && b.fromInstallment() <= a.toInstallment()) {
                    throw new IllegalArgumentException("환수 구간이 겹칩니다: " + a + " / " + b);
                }
            }
        }
    }

    /** 경과 회차에 해당하는 환수율. 구간 밖이면 empty(환수 없음). */
    public Optional<Rate> rateFor(int elapsedInstallments) {
        return bands.stream()
                .filter(b -> b.covers(elapsedInstallments))
                .map(Band::clawbackPct)
                .findFirst();
    }
}
