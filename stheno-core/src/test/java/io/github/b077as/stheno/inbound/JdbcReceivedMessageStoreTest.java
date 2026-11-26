package io.github.b077as.stheno.inbound;

import io.github.b077as.stheno.TestDatabases;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
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
    void registrationLeftUnfinishedByACrashIsTakenOverByALaterDelivery() {
        store.register(KEY);
        clock.now = clock.now.plus(Duration.ofMinutes(9));
        assertThat(store.register(KEY)).isPresent();
        clock.now = clock.now.plus(Duration.ofMinutes(2));
        assertThat(store.register(KEY)).isEmpty();
        // The new owner holds it again for the full processing timeout
        assertThat(store.register(KEY)).isPresent();
    }

    @Test
    void completedMessageIsNeverTakenOver() {
        store.register(KEY);
        store.complete(KEY, InboundOutcome.accepted("DK"));
        clock.now = clock.now.plus(Duration.ofDays(1));
        assertThat(store.register(KEY)).isPresent();
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
    void worksWhenThePoolDoesNotAutoCommit() {
        DataSource pool = TestDatabases.withoutAutoCommit(TestDatabases.h2(""));
        JdbcReceivedMessageStore pooled = new JdbcReceivedMessageStore(pool, "messages", Duration.ofDays(7), clock);
        pooled.createTableIfMissing();

        assertThat(pooled.register(KEY)).isEmpty();
        assertThat(pooled.register(KEY)).isPresent();
        pooled.complete(KEY, InboundOutcome.accepted("DK"));
        assertThat(pooled.find(KEY).orElseThrow().getOutcome().getReceiverCountry()).isEqualTo("DK");
        pooled.release(KEY);
        assertThat(pooled.find(KEY)).isEmpty();
        pooled.register(KEY);
        clock.now = clock.now.plus(Duration.ofDays(8));
        assertThat(pooled.purgeExpired()).isEqualTo(1);
        assertThat(pooled.purgeExpired()).isZero();
    }

    @Test
    void nodesInDifferentTimeZonesAgreeOnTheAgeOfARegistration() {
        node("America/Los_Angeles").register(KEY);
        // Only a minute old: still being handled, whatever the zone of the node that asks
        clock.now = clock.now.plus(Duration.ofMinutes(1));
        assertThat(node("Asia/Tokyo").register(KEY)).get().extracting(ReceivedRecord::getReceivedAt)
                .isEqualTo(Instant.parse("2026-11-15T10:00:00Z"));
    }

    @Test
    void endOfSummerTimeDoesNotAgeARegistration() {
        JdbcReceivedMessageStore brussels = node("Europe/Brussels");
        // One minute after the clocks went back from 03:00 to 02:00
        clock.now = Instant.parse("2027-10-31T01:01:00Z");
        brussels.register(KEY);
        clock.now = clock.now.plus(Duration.ofMinutes(2));
        assertThat(brussels.register(KEY)).isPresent();
    }

    private JdbcReceivedMessageStore node(String timeZone) {
        return new JdbcReceivedMessageStore(TestDatabases.inTimeZone(dataSource, timeZone), "stheno_received_message",
                Duration.ofDays(7), clock);
    }

    @Test
    void tableInANamedSchemaSurvivesARestart() throws SQLException {
        execute("CREATE SCHEMA peppol");
        JdbcReceivedMessageStore named = new JdbcReceivedMessageStore(dataSource, "peppol.messages", Duration.ofDays(7), clock);
        named.createTableIfMissing();
        named.createTableIfMissing();
        assertThat(named.register(KEY)).isEmpty();
    }

    @Test
    void tableOfTheSameNameInAnotherSchemaIsNotMistakenForOurs() throws SQLException {
        execute("CREATE SCHEMA other");
        execute("CREATE TABLE other.messages (x INTEGER)");
        JdbcReceivedMessageStore own = new JdbcReceivedMessageStore(dataSource, "messages", Duration.ofDays(7), clock);
        own.createTableIfMissing();
        assertThat(own.register(KEY)).isEmpty();
    }

    @Test
    void nodesStartingTogetherCanAllCreateTheTable() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            // The collision is a matter of timing: try often
            for (int round = 0; round < 100; round++) {
                JdbcReceivedMessageStore shared = new JdbcReceivedMessageStore(dataSource, "shared_" + round, Duration.ofDays(7));
                List<Callable<Object>> nodes = new ArrayList<>();
                for (int i = 0; i < 8; i++) {
                    nodes.add(() -> {
                        shared.createTableIfMissing();
                        return null;
                    });
                }
                for (Future<Object> f : pool.invokeAll(nodes)) {
                    f.get();
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private void execute(String sql) throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate(sql);
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
