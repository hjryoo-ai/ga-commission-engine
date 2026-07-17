package ga.comm.infra.store;

import ga.comm.domain.id.CommTypeCode;
import ga.comm.domain.id.InsurerCode;
import ga.comm.domain.id.PolicyNo;
import ga.comm.domain.id.ProductKey;
import ga.comm.domain.money.Money;
import ga.comm.domain.time.CloseYm;
import ga.comm.infra.JsonMaps;
import ga.comm.infra.mapper.InboundStatementMapper;
import ga.comm.inbound.InboundStatement;
import ga.comm.inbound.InboundStatementStore;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;
import java.util.Objects;

/** INBOUND_STATEMENT Oracle 어댑터 — statement_key 유니크 위반은 중복 수신으로 건너뛴다. */
public class OracleInboundStatementStore implements InboundStatementStore {

    private final InboundStatementMapper mapper;

    public OracleInboundStatementStore(InboundStatementMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper);
    }

    @Override
    public int saveAll(List<InboundStatement> statements) {
        int saved = 0;
        for (InboundStatement statement : statements) {
            InboundStatementMapper.Row row = new InboundStatementMapper.Row();
            row.statementKey = statement.statementKey();
            row.insurerCd = statement.insurerCd().value();
            row.statementYm = statement.statementYm().value();
            row.policyNo = statement.policyNo().value();
            row.productKey = statement.productKey().value();
            row.commType = statement.commType().value();
            row.installmentNo = statement.installmentNo();
            row.amount = statement.amount().toLong();
            row.rawFields = JsonMaps.write(statement.rawFields());
            try {
                mapper.insert(row);
                saved++;
            } catch (DuplicateKeyException duplicate) {
                // 멱등 수신 — 같은 statement_key는 최초 저장분이 기준이다
            }
        }
        return saved;
    }

    @Override
    public List<InboundStatement> findByYmAndInsurer(CloseYm statementYm, InsurerCode insurerCd) {
        return mapper.findByYmAndInsurer(statementYm.value(), insurerCd.value()).stream()
                .map(row -> new InboundStatement(new InsurerCode(row.insurerCd),
                        CloseYm.of(row.statementYm), new PolicyNo(row.policyNo),
                        new ProductKey(row.productKey), new CommTypeCode(row.commType),
                        row.installmentNo, Money.won(row.amount), JsonMaps.read(row.rawFields)))
                .toList();
    }
}
