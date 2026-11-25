package io.github.b077as.stheno.inbound;

import io.github.b077as.stheno.SthenoException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * {@link ReceivedMessageStore} in a relational database, safe for applications running on several nodes: registration relies on the
 * primary key, so of two nodes receiving the same message only one wins. Plain JDBC, no framework needed.
 * <p>
 * Table (created by {@link #createTableIfMissing()} or by the application's own migrations):
 * <pre>
 * CREATE TABLE stheno_received_message (
 *   instance_id      VARCHAR(255) NOT NULL,
 *   receiver_id      VARCHAR(255) NOT NULL,
 *   received_at      TIMESTAMP    NOT NULL,
 *   outcome_status   VARCHAR(20),
 *   receiver_country VARCHAR(2),
 *   outcome_text     VARCHAR(2000),
 *   PRIMARY KEY (instance_id, receiver_id))
 * </pre>
 * Only what is needed to answer a duplicate delivery is kept (status and receiver country), not the MLS issues.
 * <p>
 * A registration still without a decision after the <b>processing timeout</b> counts as abandoned (the node crashed or was stopped):
 * the next delivery of that message takes it over. The timeout must be longer than the slowest handler, or a delivery during a very
 * long handling runs it a second time. The documents themselves are not stored, so the Access Point has to deliver them again.
 */
public final class JdbcReceivedMessageStore implements ReceivedMessageStore {

    private static final Pattern TABLE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_.]{0,127}");

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

    /** Creates the table if it does not exist (standard SQL; use own migrations for special databases). */
    public void createTableIfMissing() {
        try (Connection c = dataSource.getConnection()) {
            try (ResultSet rs = c.getMetaData().getTables(null, null, null, new String[] {"TABLE"})) {
                while (rs.next()) {
                    if (table.equalsIgnoreCase(rs.getString("TABLE_NAME"))) {
                        return;
                    }
                }
            }
            try (Statement st = c.createStatement()) {
                st.executeUpdate("CREATE TABLE " + table + " (instance_id VARCHAR(255) NOT NULL, receiver_id VARCHAR(255) NOT NULL, "
                        + "received_at TIMESTAMP NOT NULL, outcome_status VARCHAR(20), receiver_country VARCHAR(2), "
                        + "outcome_text VARCHAR(2000), PRIMARY KEY (instance_id, receiver_id))");
            }
        } catch (SQLException ex) {
            throw failure("creating table " + table, ex);
        }
    }

    @Override
    public Optional<ReceivedRecord> register(MessageKey key) {
        Instant now = clock.instant();
        try (Connection c = dataSource.getConnection()) {
            // Expired entries of this key are removed first, so that a delivery after the retention counts as new
            try (PreparedStatement del = c.prepareStatement("DELETE FROM " + table
                    + " WHERE instance_id = ? AND receiver_id = ? AND received_at < ?")) {
                del.setString(1, key.getInstanceIdentifier());
                del.setString(2, key.getReceiverId());
                del.setTimestamp(3, Timestamp.from(now.minus(retention)));
                del.executeUpdate();
            }
            try (PreparedStatement ins = c.prepareStatement("INSERT INTO " + table
                    + " (instance_id, receiver_id, received_at) VALUES (?, ?, ?)")) {
                ins.setString(1, key.getInstanceIdentifier());
                ins.setString(2, key.getReceiverId());
                ins.setTimestamp(3, Timestamp.from(now));
                ins.executeUpdate();
                return Optional.empty();
            } catch (SQLException ex) {
                if (isDuplicateKey(ex)) {
                    Optional<ReceivedRecord> existing = find(c, key);
                    if (existing.isPresent()) {
                        return !existing.get().isCompleted() && takeOver(c, key, now) ? Optional.empty() : existing;
                    }
                }
                throw ex;
            }
        } catch (SQLException ex) {
            throw failure("registering " + key, ex);
        }
    }

    @Override
    public void complete(MessageKey key, InboundOutcome outcome) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("UPDATE " + table
                     + " SET outcome_status = ?, receiver_country = ?, outcome_text = ? WHERE instance_id = ? AND receiver_id = ?")) {
            st.setString(1, outcome.getStatus().name());
            st.setString(2, outcome.getReceiverCountry());
            String text = outcome.getText();
            st.setString(3, text == null ? null : text.length() > 2000 ? text.substring(0, 2000) : text);
            st.setString(4, key.getInstanceIdentifier());
            st.setString(5, key.getReceiverId());
            st.executeUpdate();
        } catch (SQLException ex) {
            throw failure("completing " + key, ex);
        }
    }

    @Override
    public void release(MessageKey key) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("DELETE FROM " + table + " WHERE instance_id = ? AND receiver_id = ?")) {
            st.setString(1, key.getInstanceIdentifier());
            st.setString(2, key.getReceiverId());
            st.executeUpdate();
        } catch (SQLException ex) {
            throw failure("releasing " + key, ex);
        }
    }

    @Override
    public Optional<ReceivedRecord> find(MessageKey key) {
        try (Connection c = dataSource.getConnection()) {
            return find(c, key);
        } catch (SQLException ex) {
            throw failure("reading " + key, ex);
        }
    }

    @Override
    public List<ReceivedRecord> findUnfinished(Duration olderThan) {
        Instant now = clock.instant();
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("SELECT instance_id, receiver_id, received_at FROM " + table
                     + " WHERE outcome_status IS NULL AND received_at < ? AND received_at >= ? ORDER BY received_at")) {
            st.setMaxRows(100);
            st.setTimestamp(1, Timestamp.from(now.minus(olderThan)));
            st.setTimestamp(2, Timestamp.from(now.minus(retention)));
            try (ResultSet rs = st.executeQuery()) {
                List<ReceivedRecord> unfinished = new ArrayList<>();
                while (rs.next()) {
                    unfinished.add(new ReceivedRecord(new MessageKey(rs.getString(1), rs.getString(2)), rs.getTimestamp(3).toInstant(),
                            null));
                }
                return unfinished;
            }
        } catch (SQLException ex) {
            throw failure("looking for unfinished messages", ex);
        }
    }

    /** Claims an abandoned registration. The condition is part of the update, so of several nodes only one succeeds. */
    private boolean takeOver(Connection c, MessageKey key, Instant now) throws SQLException {
        try (PreparedStatement st = c.prepareStatement("UPDATE " + table + " SET received_at = ?"
                + " WHERE instance_id = ? AND receiver_id = ? AND outcome_status IS NULL AND received_at < ?")) {
            st.setTimestamp(1, Timestamp.from(now));
            st.setString(2, key.getInstanceIdentifier());
            st.setString(3, key.getReceiverId());
            st.setTimestamp(4, Timestamp.from(now.minus(processingTimeout)));
            return st.executeUpdate() == 1;
        }
    }

    /** Deletes all entries older than the retention; call it regularly (e.g. daily). Returns the number of deleted rows. */
    public int purgeExpired() {
        try (Connection c = dataSource.getConnection();
             PreparedStatement st = c.prepareStatement("DELETE FROM " + table + " WHERE received_at < ?")) {
            st.setTimestamp(1, Timestamp.from(clock.instant().minus(retention)));
            return st.executeUpdate();
        } catch (SQLException ex) {
            throw failure("purging", ex);
        }
    }

    private Optional<ReceivedRecord> find(Connection c, MessageKey key) throws SQLException {
        try (PreparedStatement st = c.prepareStatement("SELECT received_at, outcome_status, receiver_country, outcome_text FROM " + table
                + " WHERE instance_id = ? AND receiver_id = ?")) {
            st.setString(1, key.getInstanceIdentifier());
            st.setString(2, key.getReceiverId());
            try (ResultSet rs = st.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                Instant receivedAt = rs.getTimestamp(1).toInstant();
                if (receivedAt.plus(retention).isBefore(clock.instant())) {
                    return Optional.empty();
                }
                return Optional.of(new ReceivedRecord(key, receivedAt, outcome(rs.getString(2), rs.getString(3), rs.getString(4))));
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

    private static boolean isDuplicateKey(SQLException ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof java.sql.SQLIntegrityConstraintViolationException) {
                return true;
            }
            if (t instanceof SQLException sql && sql.getSQLState() != null && sql.getSQLState().startsWith("23")) {
                return true;
            }
        }
        return false;
    }

    private static SthenoException failure(String what, SQLException ex) {
        return new SthenoException(SthenoException.ErrorCode.STORE_FAILED, "Received message store: " + what + " failed: " + ex.getMessage(), ex);
    }
}
