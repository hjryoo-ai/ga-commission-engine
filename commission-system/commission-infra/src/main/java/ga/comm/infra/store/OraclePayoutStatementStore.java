package ga.comm.infra.store;

import ga.comm.domain.money.Money;
import ga.comm.domain.time.CloseYm;
import ga.comm.domain.type.RecipientType;
import ga.comm.infra.mapper.PayoutStatementMapper;
import ga.comm.settlement.PayoutService;
import ga.comm.settlement.PayoutStatementStore;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

/** PAYOUT_STATEMENT Oracle 어댑터(V107, Phase E1 P6). */
public class OraclePayoutStatementStore implements PayoutStatementStore {

    private final PayoutStatementMapper mapper;

    public OraclePayoutStatementStore(PayoutStatementMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper);
    }

    @Override
    public void save(PayoutService.PayoutStatement s, OffsetDateTime paidAt) {
        PayoutStatementMapper.Row row = new PayoutStatementMapper.Row();
        row.closeYm = s.closeYm().value();
        row.runSeq = s.runSeq();
        row.recipientType = s.recipientType().name();
        row.recipientId = s.recipientId();
        row.payable = s.payable().toLong();
        row.incomeTax = s.incomeTax().toLong();
        row.localTax = s.localTax().toLong();
        row.netPay = s.netPay().toLong();
        row.paidAt = Objects.requireNonNull(paidAt);
        mapper.insert(row);
    }

    @Override
    public List<Saved> findByCloseYm(CloseYm closeYm) {
        return mapper.findByCloseYm(closeYm.value()).stream()
                .map(r -> new Saved(new PayoutService.PayoutStatement(CloseYm.of(r.closeYm), r.runSeq,
                        RecipientType.valueOf(r.recipientType), r.recipientId, Money.won(r.payable),
                        Money.won(r.incomeTax), Money.won(r.localTax), Money.won(r.netPay)), r.paidAt))
                .toList();
    }
}
