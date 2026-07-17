package ga.comm.api.disclosure;

import java.util.List;

/**
 * 공시 <b>서식 매핑 계층</b> (설계서 §1, Phase 15). 추출 계층의 {@link DisclosureAggregate}를 특정
 * 공시 서식(협회/당국 정본)으로 변환한다. <b>서식은 외부 확정 사안(§11 #13)이므로 하드코딩하지 않고
 * 이 인터페이스의 구현으로 갈아끼운다</b> — 서식이 바뀌어도 추출 계층·순액 산출은 그대로다.
 */
public interface DisclosureFormat {

    /** 서식 이름 (협회 서식 등). */
    String name();

    /** 원천 집계를 이 서식의 표(헤더 + 행)로 매핑한다. */
    FormattedDisclosure render(DisclosureAggregate aggregate);

    /** 서식 무관 출력 표 — 헤더와 문자열 셀 행. 실제 제출 포맷(엑셀/XML/API)은 이 표에서 파생한다. */
    record FormattedDisclosure(String formatName, List<String> headers, List<List<String>> rows) {
    }
}
