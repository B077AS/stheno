package io.github.b077as.stheno.inbound;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcReceivedMessageStoreTest {

    private static final MessageKey KEY = new MessageKey("id-1", "iso6523-actorid-upis::0088:abc");

    private JdbcDataSource dataSource;
    private MutableClock clock;
    private JdbcReceivedMessageStore store;

    @BeforeEach
    void setUp() {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        clock = new MutableClock(Instant.parse("2026-11-15T10:00:00Z"));
        store = new JdbcReceivedMessageStore(dataSource, "stheno_received_message", Duration.ofDays(7), clock);
        store.createTableIfMissing();
        store.createTableIfMissing();
    }

    @Test
    void registerCompleteFindRelease() {
        assertThat(store.register(KEY)).isEmpty();
        assertThat(store.register(KEY)).get().extracting(ReceivedRecord::isCompleted).isEqualTo(false);
        store.complete(KEY, InboundOutcome.accepted("DK"));
        ReceivedRecord rec = store.find(KEY).orElseThrow();
        assertThat(rec.getOutcome().getStatus()).isEqualTo(InboundOutcome.Status.ACCEPTED);
        assertThat(rec.getOutcome().getReceiverCountry()).isEqualTo("DK");
        assertThat(rec.getReceivedAt()).isEqualTo(clock.instant());
        store.release(KEY);
        assertThat(store.find(KEY)).isEmpty();
    }

    @Test
    void rejectionIsRemembered() {
        store.register(KEY);
        store.complete(KEY, InboundOutcome.rejected(io.github.b077as.stheno.inbound.MlsIssue.businessRule(null, "unknown buyer")));
        assertThat(store.register(KEY)).get().extracting(r -> r.getOutcome().getMlsResponseCode()).isEqualTo("RE");
    }

    @Test
    void expiredEntriesCountAsNew() {
        store.register(KEY);
        clock.now = clock.now.plus(Duration.ofDays(8));
        assertThat(store.find(KEY)).isEmpty();
        assertThat(store.register(KEY)).isEmpty();
        clock.now = clock.now.plus(Duration.ofDays(8));
        assertThat(store.purgeExpired()).isEqualTo(1);
    }

    @Test
    void concurrentRegistrationsLetExactlyOneWin() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Optional<ReceivedRecord>>> tasks = new ArrayList<>();
            for (int i = 0; i < 32; i++) {
                tasks.add(() -> store.register(KEY));
            }
            long winners = 0;
            for (Future<Optional<ReceivedRecord>> f : pool.invokeAll(tasks)) {
                if (f.get().isEmpty()) {
                    winners++;
                }
            }
            assertThat(winners).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void tableNameIsChecked() {
        assertThatThrownBy(() -> new JdbcReceivedMessageStore(dataSource, "x; DROP TABLE y", Duration.ofDays(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static final class MutableClock extends Clock {
        Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
