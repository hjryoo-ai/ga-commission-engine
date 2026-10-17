package ga.comm.outbox.fixture;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Objects;

/**
 * 단계 시계(E2 Q9) — 시나리오가 사실마다 업무 시각을 정한다({@link #set}). 시험·로컬 스택 실행기 전용이며 운영 jar에는 없다(이 모듈의
 * testFixtures). 한 스레드에서만 쓴다.
 */
public final class SteppingClock extends Clock {

    private final ZoneId zone;
    private Instant now;

    public SteppingClock(Instant start, ZoneId zone) {
        this.now = Objects.requireNonNull(start, "start");
        this.zone = Objects.requireNonNull(zone, "zone");
    }

    /** 다음 사실의 시각으로 옮긴다. */
    public void set(OffsetDateTime at) {
        now = at.toInstant();
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId other) {
        return new SteppingClock(now, other);
    }

    @Override
    public Instant instant() {
        return now;
    }
}
