package ga.comm.inbound;

import ga.comm.domain.id.InsurerCode;
import ga.comm.domain.time.CloseYm;

import java.util.List;

/** INBOUND 명세 원장 저장 포트 — statementKey 멱등 저장. */
public interface InboundStatementStore {

    /** 저장 결과: 신규 저장 건수 (중복 키는 건너뛴다). */
    int saveAll(List<InboundStatement> statements);

    List<InboundStatement> findByYmAndInsurer(CloseYm statementYm, InsurerCode insurerCd);
}
