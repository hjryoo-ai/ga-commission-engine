package ga.comm.settlement.fixture;

import ga.comm.domain.time.CloseYm;
import ga.comm.settlement.PayoutService;
import ga.comm.settlement.PayoutStatementStore;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/** 인메모리 지급명세 저장소(PAYOUT_STATEMENT 레퍼런스). */
public class InMemoryPayoutStatementStore implements PayoutStatementStore {

    private final List<Saved> rows = new ArrayList<>();

    @Override
    public synchronized void save(PayoutService.PayoutStatement statement, OffsetDateTime paidAt) {
        rows.add(new Saved(statement, paidAt));
    }

    @Override
    public synchronized List<Saved> findByCloseYm(CloseYm closeYm) {
        return rows.stream().filter(r -> r.statement().closeYm().equals(closeYm)).toList();
    }
}
