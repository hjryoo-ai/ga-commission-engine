package ga.comm.api.disclosure;

import ga.comm.domain.money.Money;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 더미 공시 서식 2종 (Phase 15) — 확정 서식이 아니라, "같은 원천 집계에서 서식만 갈아끼우면 다른 공시가
 * 나온다"를 실증하기 위한 표본이다. 실제 협회/당국 서식이 확정되면 이 인터페이스 구현을 추가·교체한다
 * (추출 계층·순액 산출은 무수정).
 */
public final class DisclosureFormats {

    private DisclosureFormats() {
    }

    /** 서식 A: 상품별 × 수수료유형 순액 피벗 (보험사·수급자·기간은 롤업). */
    public static DisclosureFormat productByCommType() {
        return new DisclosureFormat() {
            @Override
            public String name() {
                return "상품별-유형별-피벗(서식A)";
            }

            @Override
            public FormattedDisclosure render(DisclosureAggregate agg) {
                TreeSet<String> commTypes = new TreeSet<>();
                // product -> commType -> net(won)
                TreeMap<String, TreeMap<String, Long>> byProduct = new TreeMap<>();
                for (DisclosureAggregate.Figure f : agg.figures()) {
                    commTypes.add(f.commType().value());
                    byProduct.computeIfAbsent(f.productKey().value(), k -> new TreeMap<>())
                            .merge(f.commType().value(), f.net().toLong(), Long::sum);
                }
                List<String> headers = new ArrayList<>();
                headers.add("상품");
                headers.addAll(commTypes);
                headers.add("합계");

                List<List<String>> rows = new ArrayList<>();
                byProduct.forEach((product, perType) -> {
                    List<String> row = new ArrayList<>();
                    row.add(product);
                    long total = 0;
                    for (String ct : commTypes) {
                        long v = perType.getOrDefault(ct, 0L);
                        total += v;
                        row.add(String.valueOf(v));
                    }
                    row.add(String.valueOf(total));
                    rows.add(row);
                });
                return new FormattedDisclosure(name(), headers, rows);
            }
        };
    }

    /** 서식 B: 보험사별 판매수수료 순액 총계 (상품·유형·기간은 롤업). */
    public static DisclosureFormat insurerTotal() {
        return new DisclosureFormat() {
            @Override
            public String name() {
                return "보험사별-총계(서식B)";
            }

            @Override
            public FormattedDisclosure render(DisclosureAggregate agg) {
                TreeMap<String, Long> byInsurer = new TreeMap<>();
                for (DisclosureAggregate.Figure f : agg.figures()) {
                    byInsurer.merge(f.insurerCd().value(), f.net().toLong(), Long::sum);
                }
                List<List<String>> rows = new ArrayList<>();
                byInsurer.forEach((insurer, net) ->
                        rows.add(List.of(insurer, String.valueOf(net))));
                return new FormattedDisclosure(name(), List.of("보험사", "판매수수료순액"), rows);
            }
        };
    }

    /** 편의: figure 순액 합(검증용). */
    static Money totalNet(DisclosureAggregate agg) {
        return agg.figures().stream().map(DisclosureAggregate.Figure::net)
                .reduce(Money.ZERO, Money::plus);
    }
}
