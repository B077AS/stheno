package io.github.b077as.stheno.delivery;

import io.github.b077as.stheno.SthenoException;
import io.github.b077as.stheno.inbound.MlsIssue;
import io.github.b077as.stheno.inbound.ReceivedMls;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * {@link DeliveryStore} in a relational database, safe for applications running on several nodes. Plain JDBC, no framework needed. The
 * store runs its own short transactions on connections of the data source.
 * <p>
 * Tables (created by {@link #createTablesIfMissing()} or by the application's own migrations):
 * <pre>
 * CREATE TABLE stheno_sent_document (
 *   instance_id         VARCHAR(255) NOT NULL PRIMARY KEY,
 *   created_at          TIMESTAMP    NOT NULL,
 *   sent_at             TIMESTAMP,
 *   positive_requested  INTEGER      NOT NULL,
 *   mls_code            VARCHAR(2),
 *   mls_id              VARCHAR(255),
 *   mls_sender          VARCHAR(255),
 *   mls_text            VARCHAR(2000),
 *   mls_received_at     TIMESTAMP,
 *   overdue_reported_at TIMESTAMP)
 *
 * CREATE TABLE stheno_sent_document_issue (
 *   instance_id VARCHAR(255)  NOT NULL,
 *   issue_no    INTEGER       NOT NULL,
 *   reason      VARCHAR(2)    NOT NULL,
 *   location    VARCHAR(1000) NOT NULL,
 *   description VARCHAR(2000) NOT NULL,
 *   PRIMARY KEY (instance_id, issue_no))
 * </pre>
 * Longer texts of an MLS are cut to the column size. No document content is stored.
 */
public final class JdbcDeliveryStore implements DeliveryStore {

    private static final Pattern TABLE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_.]{0,120}");

    private final DataSource dataSource;
    private final String table;
    private final String issueTable;

    /** @param table name of the document table; the issue table has the same name followed by {@code _issue} */
    public JdbcDeliveryStore(DataSource dataSource, String table) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        if (table == null || !TABLE_NAME.matcher(table).matches()) {
            throw new IllegalArgumentException("Invalid table name '" + table + "'");
        }
        this.table = table;
        this.issueTable = table + "_issue";
    }

    /** Creates the tables if they do not exist (standard SQL; use own migrations for special databases). */
    public void createTablesIfMissing() {
        inTransaction("creating the tables", c -> {
            create(c, table, "instance_id VARCHAR(255) NOT NULL PRIMARY KEY, created_at TIMESTAMP NOT NULL, sent_at TIMESTAMP, "
                    + "positive_requested INTEGER NOT NULL, mls_code VARCHAR(2), mls_id VARCHAR(255), mls_sender VARCHAR(255), "
                    + "mls_text VARCHAR(2000), mls_received_at TIMESTAMP, overdue_reported_at TIMESTAMP");
            create(c, issueTable, "instance_id VARCHAR(255) NOT NULL, issue_no INTEGER NOT NULL, reason VARCHAR(2) NOT NULL, "
                    + "location VARCHAR(1000) NOT NULL, description VARCHAR(2000) NOT NULL, PRIMARY KEY (instance_id, issue_no)");
            return null;
        });
    }

    @Override
    public TrackedDocument register(String instanceIdentifier, boolean positiveMlsRequested, Instant now) {
        return insertingOnce("registering " + instanceIdentifier, c -> {
            if (find(c, instanceIdentifier).isEmpty()) {
                update(c, "INSERT INTO " + table + " (instance_id, created_at, sent_at, positive_requested) VALUES (?, ?, ?, ?)",
                        instanceIdentifier, Timestamp.from(now), Timestamp.from(now), positiveMlsRequested ? 1 : 0);
            } else {
                // Completes a record created by an MLS that came first; leaves an already registered document alone
                update(c, "UPDATE " + table + " SET sent_at = ?, positive_requested = ? WHERE instance_id = ? AND sent_at IS NULL",
                        Timestamp.from(now), positiveMlsRequested ? 1 : 0, instanceIdentifier);
            }
            return find(c, instanceIdentifier).orElseThrow();
        });
    }

    @Override
    public TrackedDocument recordMls(ReceivedMls mls, Instant now) {
        String id = mls.getReferencedInstanceIdentifier();
        return insertingOnce("recording the MLS for " + id, c -> {
            if (find(c, id).isEmpty()) {
                update(c, "INSERT INTO " + table + " (instance_id, created_at, positive_requested) VALUES (?, ?, 0)", id,
                        Timestamp.from(now));
            }
            // The condition is part of the update, so of two different MLS exactly one is kept
            int first = update(c, "UPDATE " + table + " SET mls_code = ?, mls_id = ?, mls_sender = ?, mls_text = ?, mls_received_at = ?"
                            + " WHERE instance_id = ? AND mls_code IS NULL",
                    mls.getResponseCode(), cut(mls.getMlsId(), 255), cut(mls.getSenderId(), 255), cut(mls.getResponseText(), 2000),
                    Timestamp.from(now), id);
            if (first == 1) {
                int no = 0;
                for (MlsIssue issue : mls.getIssues()) {
                    update(c, "INSERT INTO " + issueTable + " (instance_id, issue_no, reason, location, description) VALUES (?, ?, ?, ?, ?)",
                            id, no++, issue.getReason().name(), cut(issue.getLocation(), 1000), cut(issue.getDescription(), 2000));
                }
            }
            return find(c, id).orElseThrow();
        });
    }

    @Override
    public Optional<TrackedDocument> find(String instanceIdentifier) {
        return inTransaction("reading " + instanceIdentifier, c -> find(c, instanceIdentifier));
    }

    @Override
    public List<TrackedDocument> findOverdue(Instant sentBefore, boolean onlyUnreported, int max) {
        return inTransaction("looking for overdue documents", c -> {
            try (PreparedStatement st = c.prepareStatement("SELECT instance_id, sent_at FROM " + table
                    + " WHERE mls_code IS NULL AND positive_requested = 1 AND sent_at < ?"
                    + (onlyUnreported ? " AND overdue_reported_at IS NULL" : "") + " ORDER BY sent_at")) {
                st.setMaxRows(max);
                st.setTimestamp(1, Timestamp.from(sentBefore));
                try (ResultSet rs = st.executeQuery()) {
                    List<TrackedDocument> overdue = new ArrayList<>();
                    while (rs.next()) {
                        overdue.add(new TrackedDocument(rs.getString(1), rs.getTimestamp(2).toInstant(), true, null, null));
                    }
                    return overdue;
                }
            }
        });
    }

    @Override
    public void markOverdueReported(String instanceIdentifier, Instant now) {
        inTransaction("marking " + instanceIdentifier + " as reported", c -> update(c,
                "UPDATE " + table + " SET overdue_reported_at = ? WHERE instance_id = ?", Timestamp.from(now), instanceIdentifier));
    }

    @Override
    public int purge(Instant createdBefore) {
        return inTransaction("purging", c -> {
            update(c, "DELETE FROM " + issueTable + " WHERE instance_id IN (SELECT instance_id FROM " + table + " WHERE created_at < ?)",
                    Timestamp.from(createdBefore));
            return update(c, "DELETE FROM " + table + " WHERE created_at < ?", Timestamp.from(createdBefore));
        });
    }

    private Optional<TrackedDocument> find(Connection c, String id) throws SQLException {
        try (PreparedStatement st = c.prepareStatement("SELECT sent_at, positive_requested, mls_code, mls_id, mls_sender, mls_text,"
                + " mls_received_at FROM " + table + " WHERE instance_id = ?")) {
            st.setString(1, id);
            try (ResultSet rs = st.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                ReceivedMls mls = rs.getString(3) == null ? null
                        : new ReceivedMls(rs.getString(3), id, rs.getString(6), issues(c, id), rs.getString(4), rs.getString(5));
                return Optional.of(new TrackedDocument(id, instant(rs.getTimestamp(1)), rs.getInt(2) == 1, mls,
                        instant(rs.getTimestamp(7))));
            }
        }
    }

    private List<MlsIssue> issues(Connection c, String id) throws SQLException {
        try (PreparedStatement st = c.prepareStatement("SELECT reason, location, description FROM " + issueTable
                + " WHERE instance_id = ? ORDER BY issue_no")) {
            st.setString(1, id);
            try (ResultSet rs = st.executeQuery()) {
                List<MlsIssue> issues = new ArrayList<>();
                while (rs.next()) {
                    // Some databases (Oracle) return null for an empty string
                    String description = rs.getString(3);
                    issues.add(new MlsIssue(MlsIssue.Reason.valueOf(rs.getString(1)), rs.getString(2),
                            description == null ? "" : description));
                }
                return List.copyOf(issues);
            }
        }
    }

    /** Runs work that may insert a row; if another thread or node inserted it first, runs it once more (it then finds the row). */
    private <T> T insertingOnce(String what, Work<T> work) {
        try {
            return inTransaction(what, work);
        } catch (SthenoException ex) {
            if (!isDuplicateKey(ex)) {
                throw ex;
            }
            return inTransaction(what, work);
        }
    }

    private <T> T inTransaction(String what, Work<T> work) {
        try (Connection c = dataSource.getConnection()) {
            boolean autoCommit = c.getAutoCommit();
            int isolation = c.getTransactionIsolation();
            // After an update that found the row taken, the following read must show what the other transaction committed
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            c.setAutoCommit(false);
            boolean committed = false;
            try {
                T result = work.run(c);
                c.commit();
                committed = true;
                return result;
            } finally {
                if (!committed) {
                    c.rollback();
                }
                c.setAutoCommit(autoCommit);
                c.setTransactionIsolation(isolation);
            }
        } catch (SQLException ex) {
            throw new SthenoException(SthenoException.ErrorCode.STORE_FAILED, "Delivery store: " + what + " failed: " + ex.getMessage(), ex);
        }
    }

    private static void create(Connection c, String name, String columns) throws SQLException {
        try (ResultSet rs = c.getMetaData().getTables(null, null, null, new String[] {"TABLE"})) {
            while (rs.next()) {
                if (name.equalsIgnoreCase(rs.getString("TABLE_NAME"))) {
                    return;
                }
            }
        }
        try (Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE " + name + " (" + columns + ")");
        }
    }

    private static int update(Connection c, String sql, Object... values) throws SQLException {
        try (PreparedStatement st = c.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) {
                st.setObject(i + 1, values[i]);
            }
            return st.executeUpdate();
        }
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static String cut(String text, int max) {
        return text == null || text.length() <= max ? text : text.substring(0, max);
    }

    private static boolean isDuplicateKey(Throwable ex) {
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

    @FunctionalInterface
    private interface Work<T> {
        T run(Connection c) throws SQLException;
    }
}
