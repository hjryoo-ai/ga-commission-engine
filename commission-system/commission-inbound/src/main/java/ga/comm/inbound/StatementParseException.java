package ga.comm.inbound;

/** 보험사 명세 파싱 실패 — 행 번호와 원문을 담아 운영자가 원인을 추적할 수 있게 한다. */
public class StatementParseException extends RuntimeException {

    public StatementParseException(int lineNo, String rawLine, String reason) {
        super("명세 파싱 실패 (line " + lineNo + "): " + reason + " — " + rawLine);
    }
}
