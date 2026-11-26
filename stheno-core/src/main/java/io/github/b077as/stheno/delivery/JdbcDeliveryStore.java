package io.github.b077as.stheno.delivery;

import io.github.b077as.stheno.SthenoException;
import io.github.b077as.stheno.inbound.MlsIssue;
import io.github.b077as.stheno.inbound.ReceivedMls;
import io.github.b077as.stheno.reply.InvoiceReference;
import io.github.b077as.stheno.reply.InvoiceResponseBuilder;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

import static io.github.b077as.stheno.internal.JdbcSupport.Work;
import static io.github.b077as.stheno.internal.JdbcSupport.bind;
import static io.github.b077as.stheno.internal.JdbcSupport.createTableIfMissing;
import static io.github.b077as.stheno.internal.JdbcSupport.cut;
import static io.github.b077as.stheno.internal.JdbcSupport.inTransaction;
import static io.github.b077as.stheno.internal.JdbcSupport.instant;
import static io.github.b077as.stheno.internal.JdbcSupport.isDuplicateKey;
import static io.github.b077as.stheno.internal.JdbcSupport.localDate;
import static io.github.b077as.stheno.internal.JdbcSupport.update;

/**
 * {@link DeliveryStore} in a relational database, safe for applications running on several nodes. Plain JDBC, no framework needed. The
 * store runs its own short transactions and commits them, whatever the auto-commit setting of the pool. The data source must hand out
 * connections of their own, not the one of a transaction the caller is in (the Spring Boot starter sees to that for Spring's
 * transaction aware proxy). Times are stored as UTC.
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
 *   overdue_reported_at TIMESTAMP,
 *   invoice_sender      VARCHAR(255),
 *   invoice_receiver    VARCHAR(255),
 *   invoice_number      VARCHAR(255),
 *   invoice_type        VARCHAR(255),
 *   invoice_date        DATE)
 * CREATE INDEX stheno_sent_document_inv ON stheno_sent_document (invoice_number, invoice_sender)
 *
 * CREATE TABLE stheno_sent_document_issue (
 *   instance_id VARCHAR(255)  NOT NULL,
 *   issue_no    INTEGER       NOT NULL,
 *   reason      VARCHAR(2)    NOT NULL,
 *   location    VARCHAR(1000) NOT NULL,
 *   description VARCHAR(2000) NOT NULL,
 *   PRIMARY KEY (instance_id, issue_no))
 *
 * CREATE TABLE stheno_sent_document_response (
 *   invoice_sender   VARCHAR(255) NOT NULL,
 *   envelope_id      VARCHAR(255) NOT NULL,
 *   invoice_receiver VARCHAR(255) NOT NULL,
 *   invoice_number   VARCHAR(255) NOT NULL,
 *   invoice_type     VARCHAR(255) NOT NULL,
 *   invoice_date     DATE,
 *   response_status  VARCHAR(2)   NOT NULL,
 *   response_id      VARCHAR(255),
 *   issue_date       DATE,
 *   effective_date   DATE,
 *   response_note    VARCHAR(2000),
 *   received_at      TIMESTAMP    NOT NULL,
 *   PRIMARY KEY (invoice_sender, envelope_id))
 * CREATE INDEX stheno_sent_document_response_inv ON stheno_sent_document_response (invoice_number, invoice_sender)
 *
 * CREATE TABLE stheno_sent_document_response_entry (
 *   invoice_sender   VARCHAR(255) NOT NULL,
 *   envelope_id      VARCHAR(255) NOT NULL,
 *   line_no          INTEGER      NOT NULL,
 *   entry_no         INTEGER      NOT NULL,
 *   list_id          VARCHAR(255),
 *   status_code      VARCHAR(255),
 *   entry_text       VARCHAR(2000),
 *   has_condition    INTEGER      NOT NULL,
 *   attribute_id     VARCHAR(255),
 *   condition_text   VARCHAR(2000),
 *   PRIMARY KEY (invoice_sender, envelope_id, line_no))
 * </pre>
 * On MySQL and MariaDB the times are {@code DATETIME(6)}; on SQL Server they are {@code DATETIME2(6)} and the free texts
 * {@code NVARCHAR}. Longer texts of an answer are cut to the column size. No document content is stored.
 */
public final class JdbcDeliveryStore implements DeliveryStore {

    private static final Pattern TABLE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_.]{0,120}");
    private static final String DOCUMENT_COLUMNS = "instance_id, sent_at, positive_requested, mls_code, mls_id, mls_sender, mls_text,"
            + " mls_received_at, invoice_sender, invoice_receiver, invoice_number, invoice_type, invoice_date";
    private static final String SAME_INVOICE = "invoice_number = ? AND invoice_sender = ? AND invoice_receiver = ? AND invoice_type = ?";

    private final DataSource dataSource;
    private final String table;
    private final String issueTable;
    private final String responseTable;
    private final String entryTable;

    /**
     * @param table name of the document table; the other tables have the same name followed by {@code _issue}, {@code _response} and
     *              {@code _response_entry}
     */
    public JdbcDeliveryStore(DataSource dataSource, String table) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        if (table == null || !TABLE_NAME.matcher(table).matches()) {
            throw new IllegalArgumentException("Invalid table name '" + table + "'");
        }
        this.table = table;
        this.issueTable = table + "_issue";
        this.responseTable = table + "_response";
        this.entryTable = table + "_response_entry";
    }

    /** Creates the tables if they do not exist (use own migrations for databases they do not suit). */
    public void createTablesIfMissing() {
        create(table, "instance_id VARCHAR(255) NOT NULL PRIMARY KEY, created_at <timestamp> NOT NULL, sent_at <timestamp>, "
                        + "positive_requested INTEGER NOT NULL, mls_code VARCHAR(2), mls_id <text>(255), mls_sender <text>(255), "
                        + "mls_text <text>(2000), mls_received_at <timestamp>, overdue_reported_at <timestamp>, "
                        + "invoice_sender VARCHAR(255), invoice_receiver VARCHAR(255), invoice_number <text>(255), "
                        + "invoice_type VARCHAR(255), invoice_date DATE",
                indexByInvoice(table));
        create(issueTable, "instance_id VARCHAR(255) NOT NULL, issue_no INTEGER NOT NULL, reason VARCHAR(2) NOT NULL, "
                + "location <text>(1000) NOT NULL, description <text>(2000) NOT NULL, PRIMARY KEY (instance_id, issue_no)");
        create(responseTable, "invoice_sender VARCHAR(255) NOT NULL, envelope_id VARCHAR(255) NOT NULL, "
                        + "invoice_receiver VARCHAR(255) NOT NULL, invoice_number <text>(255) NOT NULL, "
                        + "invoice_type VARCHAR(255) NOT NULL, invoice_date DATE, response_status VARCHAR(2) NOT NULL, "
                        + "response_id <text>(255), issue_date DATE, effective_date DATE, response_note <text>(2000), "
                        + "received_at <timestamp> NOT NULL, PRIMARY KEY (invoice_sender, envelope_id)",
                indexByInvoice(responseTable));
        create(entryTable, "invoice_sender VARCHAR(255) NOT NULL, envelope_id VARCHAR(255) NOT NULL, line_no INTEGER NOT NULL, "
                + "entry_no INTEGER NOT NULL, list_id <text>(255), status_code <text>(255), entry_text <text>(2000), "
                + "has_condition INTEGER NOT NULL, attribute_id <text>(255), condition_text <text>(2000), "
                + "PRIMARY KEY (invoice_sender, envelope_id, line_no)");
    }

    private void create(String name, String columns, String... indexes) {
        try {
            createTableIfMissing(dataSource, name, columns, indexes);
        } catch (SQLException ex) {
            throw failure("creating table " + name, ex);
        }
    }

    private static String indexByInvoice(String name) {
        return "CREATE INDEX " + name.replace('.', '_') + "_inv ON " + name + " (invoice_number, invoice_sender)";
    }

    @Override
    public TrackedDocument register(String instanceIdentifier, boolean positiveMlsRequested, InvoiceReference invoice, Instant now) {
        Object[] reference = invoice == null ? new Object[5] : new Object[] {invoice.getInvoiceSenderId(), invoice.getInvoiceReceiverId(),
                invoice.getInvoiceNumber(), invoice.getTypeCode(), invoice.getIssueDate()};
        return insertingOnce("registering " + instanceIdentifier, c -> {
            if (find(c, instanceIdentifier).isEmpty()) {
                update(c, "INSERT INTO " + table + " (instance_id, created_at, sent_at, positive_requested, invoice_sender,"
                                + " invoice_receiver, invoice_number, invoice_type, invoice_date) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        instanceIdentifier, now, now, positiveMlsRequested ? 1 : 0, reference[0],
                        reference[1], reference[2], reference[3], reference[4]);
            } else {
                // Completes a record created by an MLS that came first; leaves an already registered document alone
                update(c, "UPDATE " + table + " SET sent_at = ?, positive_requested = ?, invoice_sender = ?, invoice_receiver = ?,"
                                + " invoice_number = ?, invoice_type = ?, invoice_date = ? WHERE instance_id = ? AND sent_at IS NULL",
                        now, positiveMlsRequested ? 1 : 0, reference[0], reference[1], reference[2], reference[3],
                        reference[4], instanceIdentifier);
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
                        now);
            }
            // The condition is part of the update, so of two different MLS exactly one is kept
            int first = update(c, "UPDATE " + table + " SET mls_code = ?, mls_id = ?, mls_sender = ?, mls_text = ?, mls_received_at = ?"
                            + " WHERE instance_id = ? AND mls_code IS NULL",
                    mls.getResponseCode(), cut(mls.getMlsId(), 255), cut(mls.getSenderId(), 255), cut(mls.getResponseText(), 2000),
                    now, id);
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
        return transaction("reading " + instanceIdentifier, c -> find(c, instanceIdentifier));
    }

    @Override
    public TrackedResponse recordResponse(InvoiceReference invoice, TrackedResponse response) {
        String sender = invoice.getInvoiceSenderId();
        String envelopeId = response.getInstanceIdentifier();
        return insertingOnce("recording the response " + envelopeId, c -> {
            Optional<TrackedResponse> kept = findResponse(c, sender, envelopeId);
            if (kept.isPresent()) {
                return kept.get();
            }
            update(c, "INSERT INTO " + responseTable + " (invoice_sender, envelope_id, invoice_receiver, invoice_number, invoice_type,"
                            + " invoice_date, response_status, response_id, issue_date, effective_date, response_note, received_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    sender, envelopeId, invoice.getInvoiceReceiverId(), invoice.getInvoiceNumber(), invoice.getTypeCode(),
                    invoice.getIssueDate(), response.getStatus().name(), cut(response.getResponseId(), 255),
                    response.getIssueDate(), response.getEffectiveDate(), cut(response.getNote(), 2000),
                    response.getReceivedAt());
            insertEntries(c, sender, envelopeId, response.getStatusEntries());
            return findResponse(c, sender, envelopeId).orElseThrow();
        });
    }

    @Override
    public List<TrackedResponse> findResponses(InvoiceReference invoice) {
        return transaction("reading the responses to invoice " + invoice.getInvoiceNumber(), c -> {
            List<String> ids = new ArrayList<>();
            try (PreparedStatement st = c.prepareStatement("SELECT envelope_id, invoice_number, invoice_type, invoice_date FROM "
                    + responseTable + " WHERE " + SAME_INVOICE + " ORDER BY received_at, envelope_id")) {
                bindInvoice(st, invoice);
                try (ResultSet rs = st.executeQuery()) {
                    while (rs.next()) {
                        if (isExactly(rs, invoice)) {
                            ids.add(rs.getString("envelope_id"));
                        }
                    }
                }
            }
            List<TrackedResponse> found = new ArrayList<>();
            for (String id : ids) {
                findResponse(c, invoice.getInvoiceSenderId(), id).ifPresent(found::add);
            }
            return List.copyOf(found);
        });
    }

    @Override
    public List<TrackedDocument> findByInvoice(InvoiceReference invoice) {
        return transaction("looking for invoice " + invoice.getInvoiceNumber(), c -> {
            try (PreparedStatement st = c.prepareStatement("SELECT " + DOCUMENT_COLUMNS + " FROM " + table + " WHERE " + SAME_INVOICE
                    + " AND sent_at IS NOT NULL ORDER BY sent_at DESC, instance_id")) {
                bindInvoice(st, invoice);
                try (ResultSet rs = st.executeQuery()) {
                    List<TrackedDocument> found = new ArrayList<>();
                    while (rs.next()) {
                        if (isExactly(rs, invoice)) {
                            found.add(document(c, rs));
                        }
                    }
                    return List.copyOf(found);
                }
            }
        });
    }

    @Override
    public List<TrackedDocument> findOverdue(Instant sentBefore, boolean onlyUnreported, int max) {
        return transaction("looking for overdue documents", c -> {
            try (PreparedStatement st = c.prepareStatement("SELECT " + DOCUMENT_COLUMNS + " FROM " + table
                    + " WHERE mls_code IS NULL AND positive_requested = 1 AND sent_at < ?"
                    + (onlyUnreported ? " AND overdue_reported_at IS NULL" : "") + " ORDER BY sent_at")) {
                st.setMaxRows(max);
                bind(st, sentBefore);
                try (ResultSet rs = st.executeQuery()) {
                    List<TrackedDocument> overdue = new ArrayList<>();
                    while (rs.next()) {
                        overdue.add(document(c, rs));
                    }
                    return overdue;
                }
            }
        });
    }

    @Override
    public void markOverdueReported(String instanceIdentifier, Instant now) {
        transaction("marking " + instanceIdentifier + " as reported", c -> update(c,
                "UPDATE " + table + " SET overdue_reported_at = ? WHERE instance_id = ?", now, instanceIdentifier));
    }

    @Override
    public int purge(Instant createdBefore) {
        return transaction("purging", c -> {
            update(c, "DELETE FROM " + entryTable + " WHERE EXISTS (SELECT 1 FROM " + responseTable + " r WHERE r.invoice_sender = "
                    + entryTable + ".invoice_sender AND r.envelope_id = " + entryTable + ".envelope_id AND r.received_at < ?)",
                    createdBefore);
            update(c, "DELETE FROM " + responseTable + " WHERE received_at < ?", createdBefore);
            update(c, "DELETE FROM " + issueTable + " WHERE instance_id IN (SELECT instance_id FROM " + table + " WHERE created_at < ?)",
                    createdBefore);
            return update(c, "DELETE FROM " + table + " WHERE created_at < ?", createdBefore);
        });
    }

    private Optional<TrackedDocument> find(Connection c, String id) throws SQLException {
        try (PreparedStatement st = c.prepareStatement("SELECT " + DOCUMENT_COLUMNS + " FROM " + table + " WHERE instance_id = ?")) {
            st.setString(1, id);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? Optional.of(document(c, rs)) : Optional.empty();
            }
        }
    }

    /** The current row of a query for {@link #DOCUMENT_COLUMNS}. */
    private TrackedDocument document(Connection c, ResultSet rs) throws SQLException {
        String id = rs.getString("instance_id");
        String code = rs.getString("mls_code");
        ReceivedMls mls = code == null ? null : new ReceivedMls(code, id, rs.getString("mls_text"), issues(c, id), rs.getString("mls_id"),
                rs.getString("mls_sender"));
        InvoiceReference invoice = rs.getString("invoice_number") == null ? null
                : InvoiceReference.of(rs.getString("invoice_sender"), rs.getString("invoice_receiver"), rs.getString("invoice_number"),
                        rs.getString("invoice_type"), localDate(rs, "invoice_date"));
        return new TrackedDocument(id, instant(rs, "sent_at"), rs.getInt("positive_requested") == 1, mls,
                instant(rs, "mls_received_at"), invoice);
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

    private Optional<TrackedResponse> findResponse(Connection c, String sender, String envelopeId) throws SQLException {
        try (PreparedStatement st = c.prepareStatement("SELECT response_status, response_id, issue_date, effective_date, response_note,"
                + " received_at FROM " + responseTable + " WHERE invoice_sender = ? AND envelope_id = ?")) {
            st.setString(1, sender);
            st.setString(2, envelopeId);
            try (ResultSet rs = st.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(TrackedResponse.builder()
                        .instanceIdentifier(envelopeId)
                        .status(InvoiceResponseBuilder.Status.valueOf(rs.getString(1)))
                        .responseId(rs.getString(2))
                        .issueDate(localDate(rs, "issue_date"))
                        .effectiveDate(localDate(rs, "effective_date"))
                        .note(rs.getString(5))
                        .receivedAt(instant(rs, "received_at"))
                        .statusEntries(entries(c, sender, envelopeId))
                        .build());
            }
        }
    }

    /** One row per condition of an entry, or one row for an entry without conditions. */
    private void insertEntries(Connection c, String sender, String envelopeId, List<InvoiceResponseBuilder.StatusEntry> entries)
            throws SQLException {
        String sql = "INSERT INTO " + entryTable + " (invoice_sender, envelope_id, line_no, entry_no, list_id, status_code, entry_text,"
                + " has_condition, attribute_id, condition_text) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        int line = 0;
        for (int no = 0; no < entries.size(); no++) {
            InvoiceResponseBuilder.StatusEntry entry = entries.get(no);
            List<InvoiceResponseBuilder.Condition> conditions = entry.getConditions();
            for (int i = 0; i < Math.max(1, conditions.size()); i++) {
                InvoiceResponseBuilder.Condition condition = conditions.isEmpty() ? null : conditions.get(i);
                update(c, sql, sender, envelopeId, line++, no, cut(entry.getListId(), 255), cut(entry.getCode(), 255),
                        cut(entry.getText(), 2000), condition == null ? 0 : 1,
                        condition == null ? null : cut(condition.getAttributeId(), 255),
                        condition == null ? null : cut(condition.getDescription(), 2000));
            }
        }
    }

    private List<InvoiceResponseBuilder.StatusEntry> entries(Connection c, String sender, String envelopeId) throws SQLException {
        try (PreparedStatement st = c.prepareStatement("SELECT entry_no, list_id, status_code, entry_text, has_condition, attribute_id,"
                + " condition_text FROM " + entryTable + " WHERE invoice_sender = ? AND envelope_id = ? ORDER BY line_no")) {
            st.setString(1, sender);
            st.setString(2, envelopeId);
            try (ResultSet rs = st.executeQuery()) {
                List<InvoiceResponseBuilder.StatusEntry> entries = new ArrayList<>();
                List<InvoiceResponseBuilder.Condition> conditions = null;
                int previous = -1;
                while (rs.next()) {
                    if (rs.getInt(1) != previous) {
                        previous = rs.getInt(1);
                        conditions = new ArrayList<>();
                        // The list is filled by the following rows of the same entry
                        entries.add(new InvoiceResponseBuilder.StatusEntry(rs.getString(2), rs.getString(3), rs.getString(4), conditions));
                    }
                    if (rs.getInt(5) == 1) {
                        conditions.add(new InvoiceResponseBuilder.Condition(rs.getString(6), rs.getString(7)));
                    }
                }
                return entries.stream()
                        .map(e -> new InvoiceResponseBuilder.StatusEntry(e.getListId(), e.getCode(), e.getText(),
                                List.copyOf(e.getConditions())))
                        .toList();
            }
        }
    }

    /**
     * Whether the current row names the same invoice, comparing with case: some databases (MySQL, MariaDB, SQL Server by default)
     * also match texts that differ in case only.
     */
    private static boolean isExactly(ResultSet rs, InvoiceReference invoice) throws SQLException {
        return invoice.getInvoiceNumber().equals(rs.getString("invoice_number"))
                && invoice.getTypeCode().equals(rs.getString("invoice_type"))
                && DeliveryStore.compatibleDates(localDate(rs, "invoice_date"), invoice.getIssueDate());
    }

    private static void bindInvoice(PreparedStatement st, InvoiceReference invoice) throws SQLException {
        bind(st, invoice.getInvoiceNumber(), invoice.getInvoiceSenderId(), invoice.getInvoiceReceiverId(), invoice.getTypeCode());
    }

    /** Runs work that may insert a row; if another thread or node inserted it first, runs it once more (it then finds the row). */
    private <T> T insertingOnce(String what, Work<T> work) {
        try {
            return transaction(what, work);
        } catch (SthenoException ex) {
            if (!isDuplicateKey(ex)) {
                throw ex;
            }
            return transaction(what, work);
        }
    }

    private <T> T transaction(String what, Work<T> work) {
        try {
            return inTransaction(dataSource, work);
        } catch (SQLException ex) {
            throw failure(what, ex);
        }
    }

    private static SthenoException failure(String what, SQLException ex) {
        return new SthenoException(SthenoException.ErrorCode.STORE_FAILED, "Delivery store: " + what + " failed: " + ex.getMessage(), ex);
    }
}
