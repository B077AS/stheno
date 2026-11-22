package io.github.b077as.stheno.inbound;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryReceivedMessageStoreTest {

    private static final MessageKey KEY = new MessageKey("id-1", "iso6523-actorid-upis::0088:abc");

    @Test
    void registerCompleteFind() {
        InMemoryReceivedMessageStore store = new InMemoryReceivedMessageStore(Duration.ofHours(1));
        assertThat(store.register(KEY)).isEmpty();
        assertThat(store.register(KEY)).isPresent().get().extracting(ReceivedRecord::isCompleted).isEqualTo(false);
        store.complete(KEY, InboundOutcome.accepted("BE"));
        assertThat(store.find(KEY)).get().extracting(r -> r.getOutcome().getReceiverCountry()).isEqualTo("BE");
        store.release(KEY);
        assertThat(store.find(KEY)).isEmpty();
    }

    @Test
    void entriesExpireAfterTheRetention() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-15T10:00:00Z"));
        InMemoryReceivedMessageStore store = new InMemoryReceivedMessageStore(Duration.ofHours(1), clock);
        assertThat(store.register(KEY)).isEmpty();
        clock.now = clock.now.plus(Duration.ofMinutes(59));
        assertThat(store.register(KEY)).isPresent();
        clock.now = clock.now.plus(Duration.ofMinutes(2));
        assertThat(store.find(KEY)).isEmpty();
        assertThat(store.register(KEY)).isEmpty();
    }

    @Test
    void concurrentRegistrationsLetExactlyOneWin() throws Exception {
        InMemoryReceivedMessageStore store = new InMemoryReceivedMessageStore(Duration.ofHours(1));
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Callable<Optional<ReceivedRecord>>> tasks = new ArrayList<>();
            for (int i = 0; i < 64; i++) {
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
    void retentionMustBePositive() {
        assertThatThrownBy(() -> new InMemoryReceivedMessageStore(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
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
