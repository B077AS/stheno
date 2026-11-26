package io.github.b077as.stheno.inbound;

import io.github.b077as.stheno.SthenoException;
import io.github.b077as.stheno.internal.JdbcSupport;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

import static io.github.b077as.stheno.internal.JdbcSupport.bind;
import static io.github.b077as.stheno.internal.JdbcSupport.cut;
import static io.github.b077as.stheno.internal.JdbcSupport.inTransaction;
import static io.github.b077as.stheno.internal.JdbcSupport.instant;
import static io.github.b077as.stheno.internal.JdbcSupport.isDuplicateKey;
import static io.github.b077as.stheno.internal.JdbcSupport.update;

/**
 * {@link ReceivedMessageStore} in a relational database, safe for applications running on several nodes: registration relies on the
 * primary key, so of two nodes receiving the same message only one wins. Plain JDBC, no framework needed.
 * <p>
 * The store runs its own short transactions and commits them, whatever the auto-commit setting of the pool. The data source must hand
 * out connections of their own, not the one of a transaction the caller is in (the Spring Boot starter sees to that for Spring's
 * transaction aware proxy).
 * <p>
 * Table (created by {@link #createTableIfMissing()} or by the application's own migrations):
 * <pre>
 * CREATE TABLE stheno_received_message (
 *   instance_id      VARCHAR(255) NOT NULL,
 *   receiver_id      VARCHAR(255) NOT NULL,
 *   received_at      TIMESTAMP    NOT NULL,     -- MySQL, MariaDB: DATETIME(6); SQL Server: DATETIME2(6)
 *   outcome_status   VARCHAR(20),
 *   receiver_country VARCHAR(2),
 *   outcome_text     VARCHAR(2000),             -- SQL Server: NVARCHAR
 *   PRIMARY KEY (instance_id, receiver_id))
 * CREATE INDEX stheno_received_message_at ON stheno_received_message (received_at)
 * </pre>
 * Times are stored as UTC. Only what is needed to answer a duplicate delivery is kept (status and receiver country), not the MLS
 * issues.
 * <p>
 * A registration still without a decision after the <b>processing timeout</b> counts as abandoned (the node crashed or was stopped):
 * the next delivery of that message takes it over. The timeout must be longer than the slowest handler, or a delivery during a very
 * long handling runs it a second time. The documents themselves are not stored, so the Access Point has to deliver them again.
 */
public final class JdbcReceivedMessageStore implements ReceivedMessageStore {

    private static final Pattern TABLE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_.]{0,127}");
    private static final int REGISTER_ATTEMPTS = 3;

    /** Processing timeout used when none is given. */
    public static final Duration DEFAULT_PROCESSING_TIMEOUT = Duration.ofMinutes(10);

    private final DataSource dataSource;
    private final String table;
    private final Duration retention;
    private final Duration processingTimeout;
    private final Clock clock;

    public JdbcReceivedMessageStore(DataSource dataSource, String table, Duration retention) {
        this(dataSource, table, retention, Clock.systemUTC());
    }

    public JdbcReceivedMessageStore(DataSource dataSource, String table, Duration retention, Clock clock) {
        this(dataSource, table, retention, DEFAULT_PROCESSING_TIMEOUT, clock);
    }

    public JdbcReceivedMessageStore(DataSource dataSource, String table, Duration retention, Duration processingTimeout, Clock clock) {
        if (processingTimeout == null || processingTimeout.isNegative() || processingTimeout.isZero()) {
            throw new IllegalArgumentException("The processing timeout must be positive");
        }
        this.processingTimeout = processingTimeout;
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        if (table == null || !TABLE_NAME.matcher(table).matches()) {
            throw new IllegalArgumentException("Invalid table name '" + table + "'");
        }
        this.table = table;
        if (retention == null || retention.isNegative() || retention.isZero()) {
            throw new IllegalArgumentException("The retention must be positive");
        }
        this.retention = retention;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Creates the table if it does not exist (use own migrations for databases it does not suit). */
    public void createTableIfMissing() {
        try {
            JdbcSupport.createTableIfMissing(dataSource, table,
                    "instance_id VARCHAR(255) NOT NULL, receiver_id VARCHAR(255) NOT NULL, received_at <timestamp> NOT NULL, "
                            + "outcome_status VARCHAR(20), receiver_country VARCHAR(2), outcome_text <text>(2000), "
                            + "PRIMARY KEY (instance_id, receiver_id)",
                    "CREATE INDEX " + table.replace('.', '_') + "_at ON " + table + " (received_at)");
        } catch (SQLException ex) {
            throw failure("creating table " + table, ex);
        }
    }

    @Override
    public Optional<ReceivedRecord> register(MessageKey key) {
        try {
            for (int attempt = 1; ; attempt++) {
                Instant now = clock.instant();
                try {
                    inTransaction(dataSource, c -> insert(c, key, now));
                    return Optional.empty();
                } catch (SQLException ex) {
                    if (!isDuplicateKey(ex)) {
                        throw ex;
                    }
                    // New transactions: some databases refuse further statements after a failed one
                    Optional<ReceivedRecord> existing = inTransaction(dataSource, c -> find(c, key));
                    if (existing.isPresent()) {
                        boolean takenOver = !existing.get().isCompleted() && inTransaction(dataSource, c -> takeOver(c, key, now));
                        return takenOver ? Optional.empty() : existing;
                    }
                    // Released between the two statements: try to register it again
                    if (attempt == REGISTER_ATTEMPTS) {
                        throw ex;
                    }
                }
            }
        } catch (SQLException ex) {
            throw failure("registering " + key, ex);
        }
    }

    @Override
    public void complete(MessageKey key, InboundOutcome outcome) {
        try {
            inTransaction(dataSource, c -> update(c, "UPDATE " + table
                            + " SET outcome_status = ?, receiver_country = ?, outcome_text = ? WHERE instance_id = ? AND receiver_id = ?",
                    outcome.getStatus().name(), outcome.getReceiverCountry(), cut(outcome.getText(), 2000), key.getInstanceIdentifier(),
                    key.getReceiverId()));
        } catch (SQLException ex) {
            throw failure("completing " + key, ex);
        }
    }

    @Override
    public void release(MessageKey key) {
        try {
            inTransaction(dataSource, c -> update(c, "DELETE FROM " + table + " WHERE instance_id = ? AND receiver_id = ?",
                    key.getInstanceIdentifier(), key.getReceiverId()));
        } catch (SQLException ex) {
            throw failure("releasing " + key, ex);
        }
    }

    @Override
    public Optional<ReceivedRecord> find(MessageKey key) {
        try {
            return inTransaction(dataSource, c -> find(c, key));
        } catch (SQLException ex) {
            throw failure("reading " + key, ex);
        }
    }

    @Override
    public List<ReceivedRecord> findUnfinished(Duration olderThan) {
        Instant now = clock.instant();
        try {
            return inTransaction(dataSource, c -> {
                try (PreparedStatement st = c.prepareStatement("SELECT instance_id, receiver_id, received_at FROM " + table
                        + " WHERE outcome_status IS NULL AND received_at < ? AND received_at >= ? ORDER BY received_at")) {
                    st.setMaxRows(100);
                    bind(st, now.minus(olderThan), now.minus(retention));
                    try (ResultSet rs = st.executeQuery()) {
                        List<ReceivedRecord> unfinished = new ArrayList<>();
                        while (rs.next()) {
                            unfinished.add(new ReceivedRecord(new MessageKey(rs.getString("instance_id"), rs.getString("receiver_id")),
                                    instant(rs, "received_at"), null));
                        }
                        return unfinished;
                    }
                }
            });
        } catch (SQLException ex) {
            throw failure("looking for unfinished messages", ex);
        }
    }

    /**
     * Deletes all entries older than the retention and returns their number. The Spring Boot starter calls it regularly; without the
     * starter, call it yourself (e.g. daily).
     */
    @Override
    public int purgeExpired() {
        try {
            return inTransaction(dataSource,
                    c -> update(c, "DELETE FROM " + table + " WHERE received_at < ?", clock.instant().minus(retention)));
        } catch (SQLException ex) {
            throw failure("purging", ex);
        }
    }

    /** Removes an expired entry of the key, so that a delivery after the retention counts as new, and registers it. */
    private int insert(Connection c, MessageKey key, Instant now) throws SQLException {
        update(c, "DELETE FROM " + table + " WHERE instance_id = ? AND receiver_id = ? AND received_at < ?",
                key.getInstanceIdentifier(), key.getReceiverId(), now.minus(retention));
        return update(c, "INSERT INTO " + table + " (instance_id, receiver_id, received_at) VALUES (?, ?, ?)",
                key.getInstanceIdentifier(), key.getReceiverId(), now);
    }

    /** Claims an abandoned registration. The condition is part of the update, so of several nodes only one succeeds. */
    private boolean takeOver(Connection c, MessageKey key, Instant now) throws SQLException {
        return update(c, "UPDATE " + table + " SET received_at = ?"
                        + " WHERE instance_id = ? AND receiver_id = ? AND outcome_status IS NULL AND received_at < ?",
                now, key.getInstanceIdentifier(), key.getReceiverId(), now.minus(processingTimeout)) == 1;
    }

    private Optional<ReceivedRecord> find(Connection c, MessageKey key) throws SQLException {
        try (PreparedStatement st = c.prepareStatement("SELECT received_at, outcome_status, receiver_country, outcome_text FROM " + table
                + " WHERE instance_id = ? AND receiver_id = ?")) {
            bind(st, key.getInstanceIdentifier(), key.getReceiverId());
            try (ResultSet rs = st.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                Instant receivedAt = instant(rs, "received_at");
                if (receivedAt.plus(retention).isBefore(clock.instant())) {
                    return Optional.empty();
                }
                return Optional.of(new ReceivedRecord(key, receivedAt, outcome(rs.getString("outcome_status"),
                        rs.getString("receiver_country"), rs.getString("outcome_text"))));
            }
        }
    }

    private static InboundOutcome outcome(String status, String country, String text) {
        if (status == null) {
            return null;
        }
        return switch (InboundOutcome.Status.valueOf(status)) {
            case ACCEPTED -> InboundOutcome.accepted(country, text, List.of());
            case ACKNOWLEDGED -> InboundOutcome.acknowledged(country);
            // The issues are not stored: a duplicate only needs to know that the document was rejected
            case REJECTED -> InboundOutcome.rejected(text, List.of(MlsIssue.businessRule(null, text == null ? "rejected" : text)));
        };
    }

    private static SthenoException failure(String what, SQLException ex) {
        return new SthenoException(SthenoException.ErrorCode.STORE_FAILED, "Received message store: " + what + " failed: " + ex.getMessage(), ex);
    }
}
