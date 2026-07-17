package ga.comm.inbound.fixture;

import ga.comm.domain.id.InsurerCode;
import ga.comm.domain.time.CloseYm;
import ga.comm.inbound.InboundStatement;
import ga.comm.inbound.InboundStatementStore;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 인메모리 INBOUND 명세 원장. */
public class InMemoryInboundStatementStore implements InboundStatementStore {

    private final List<InboundStatement> statements = new ArrayList<>();
    private final Set<String> keys = new HashSet<>();

    @Override
    public synchronized int saveAll(List<InboundStatement> newStatements) {
        int saved = 0;
        for (InboundStatement statement : newStatements) {
            if (keys.add(statement.statementKey())) {
                statements.add(statement);
                saved++;
            }
        }
        return saved;
    }

    @Override
    public synchronized List<InboundStatement> findByYmAndInsurer(CloseYm statementYm,
                                                                  InsurerCode insurerCd) {
        return statements.stream()
                .filter(s -> s.statementYm().equals(statementYm) && s.insurerCd().equals(insurerCd))
                .toList();
    }
}
