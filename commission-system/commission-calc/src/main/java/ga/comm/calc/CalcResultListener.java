package ga.comm.calc;

import ga.comm.calc.store.CommCalcRecord;

import java.util.List;

/**
 * 계산 결과 저장 직후 훅 — COMM_CALC calcId가 필요한 후속 처리
 * (한도 원장 전기, 분급 스케줄 생성 등)가 여기 연결된다.
 * DB 구현에서는 계산·저장·리스너가 동일 트랜잭션 안에서 실행된다.
 */
public interface CalcResultListener {

    void onPersisted(CalcContext ctx, List<PersistedLine> lines);

    /** 저장된 라인 — lineIndex는 CalcContext.lines() 기준 인덱스. */
    record PersistedLine(int lineIndex, CalcLine line, CommCalcRecord record) {
    }
}
