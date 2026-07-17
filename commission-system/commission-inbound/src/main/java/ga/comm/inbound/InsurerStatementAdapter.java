package ga.comm.inbound;

import ga.comm.domain.id.InsurerCode;
import ga.comm.domain.time.CloseYm;

import java.util.List;

/**
 * 보험사별 EDI/명세 어댑터 (설계서 §4.1 commission-inbound).
 * 보험사마다 포맷·수수료 유형 코드가 전부 다르므로, 보험사당 구현체 1개를 증설한다.
 */
public interface InsurerStatementAdapter {

    InsurerCode insurerCd();

    /**
     * 원문(파일 내용)을 정규화 행으로 변환한다.
     * 파싱 불가 행은 {@link StatementParseException}으로 실패시킨다 — 조용한 누락 금지.
     */
    List<InboundStatement> parse(CloseYm statementYm, String rawContent);
}
