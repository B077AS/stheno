package io.github.b077as.stheno.internal;

import lombok.extern.slf4j.Slf4j;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * What Stheno's JDBC stores share. Internal: not part of the API.
 * <p>
 * Every operation runs in its own transaction and is committed here, whatever the auto-commit setting of the pool. Points in time are
 * stored as UTC, so that nodes in different time zones, and a change to or from summer time, do not move them.
 */
@Slf4j
public final class JdbcSupport {

    private static final int CREATION_CHECKS = 20;
    private static final long CREATION_CHECK_PAUSE_MILLIS = 100;

    /** Work on one connection inside one transaction. */
    @FunctionalInterface
    public interface Work<T> {
        T run(Connection c) throws SQLException;
    }

    private JdbcSupport() {
    }

    public static <T> T inTransaction(DataSource dataSource, Work<T> work) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            boolean autoCommit = c.getAutoCommit();
            int isolation = c.getTransactionIsolation();
            // After an update that found a row taken, a following read must show what the other transaction committed
            if (isolation != Connection.TRANSACTION_READ_COMMITTED) {
                c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            }
            c.setAutoCommit(false);
            SQLException failure = null;
            boolean committed = false;
            try {
                T result = work.run(c);
                c.commit();
                committed = true;
                return result;
            } catch (SQLException ex) {
                failure = ex;
                throw ex;
            } finally {
                restore(c, committed, autoCommit, isolation, failure);
            }
        }
    }

    /** Never replaces the outcome of the work with a failure of the clean up. */
    private static void restore(Connection c, boolean committed, boolean autoCommit, int isolation, SQLException failure) {
        try {
            if (!committed) {
                c.rollback();
            }
            c.setAutoCommit(autoCommit);
            if (isolation != Connection.TRANSACTION_READ_COMMITTED) {
                c.setTransactionIsolation(isolation);
            }
        } catch (SQLException ex) {
            if (failure != null) {
                failure.addSuppressed(ex);
            } else {
                log.warn("Could not reset a database connection: {}", ex.getMessage());
            }
        }
    }

    /** Runs a statement that changes rows. {@link Instant}s and {@link LocalDate}s may be given as values. */
    public static int update(Connection c, String sql, Object... values) throws SQLException {
        try (PreparedStatement st = c.prepareStatement(sql)) {
            bind(st, values);
            return st.executeUpdate();
        }
    }

    public static void bind(PreparedStatement st, Object... values) throws SQLException {
        for (int i = 0; i < values.length; i++) {
            if (values[i] instanceof Instant instant) {
                st.setTimestamp(i + 1, Timestamp.from(instant), utc());
            } else if (values[i] instanceof LocalDate date) {
                st.setDate(i + 1, Date.valueOf(date));
            } else {
                st.setObject(i + 1, values[i]);
            }
        }
    }

    public static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column, utc());
        return timestamp == null ? null : timestamp.toInstant();
    }

    public static LocalDate localDate(ResultSet rs, String column) throws SQLException {
        Date date = rs.getDate(column);
        return date == null ? null : date.toLocalDate();
    }

    /** The text cut to a column size, never in the middle of a character. */
    public static String cut(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, Character.isHighSurrogate(text.charAt(max - 1)) ? max - 1 : max);
    }

    public static boolean isDuplicateKey(Throwable ex) {
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

    /**
     * Creates a table and its indexes if the table cannot be queried. In the column definitions and index statements,
     * {@code <timestamp>} stands for the database's type of a point in time and {@code <text>(n)} for its type of free text.
     * <p>
     * The table name is used as in every other statement, so a schema name and the database's search path apply as usual. Several
     * nodes may call this at the same time.
     *
     * @param indexes statements to run after creating the table
     */
    public static void createTableIfMissing(DataSource dataSource, String table, String columns, String... indexes) throws SQLException {
        if (tableExists(dataSource, table)) {
            return;
        }
        try {
            inTransaction(dataSource, c -> {
                String product = c.getMetaData().getDatabaseProductName();
                // Texts from the Peppol network may hold any character, whatever the default of the database is
                String options = isMySql(product) ? " DEFAULT CHARACTER SET utf8mb4" : "";
                List<String> statements = new ArrayList<>();
                statements.add("CREATE TABLE " + table + " (" + columns + ")" + options);
                statements.addAll(List.of(indexes));
                try (Statement st = c.createStatement()) {
                    for (String sql : statements) {
                        st.executeUpdate(forDatabase(sql, product));
                    }
                }
                return null;
            });
        } catch (SQLException ex) {
            if (!appearsShortly(dataSource, table)) {
                throw ex;
            }
            log.info("Table {} was created meanwhile ({})", table, ex.getMessage());
        }
    }

    /**
     * Whether another node is creating the table: its name can be taken a moment before it can be queried.
     */
    private static boolean appearsShortly(DataSource dataSource, String table) {
        for (int attempt = 1; ; attempt++) {
            if (tableExists(dataSource, table)) {
                return true;
            }
            if (attempt == CREATION_CHECKS) {
                return false;
            }
            try {
                Thread.sleep(CREATION_CHECK_PAUSE_MILLIS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    static String forDatabase(String sql, String product) {
        boolean sqlServer = isSqlServer(product);
        // SQL Server's TIMESTAMP is a row version; MySQL's drops fractions of a second and ends in 2038
        String timestamp = sqlServer ? "DATETIME2(6)" : isMySql(product) ? "DATETIME(6)" : "TIMESTAMP";
        // SQL Server's VARCHAR loses characters outside the database's code page
        return sql.replace("<timestamp>", timestamp).replace("<text>", sqlServer ? "NVARCHAR" : "VARCHAR");
    }

    private static boolean isSqlServer(String product) {
        return product != null && product.toLowerCase(Locale.ROOT).contains("sql server");
    }

    private static boolean isMySql(String product) {
        String name = product == null ? "" : product.toLowerCase(Locale.ROOT);
        return name.contains("mysql") || name.contains("mariadb");
    }

    private static boolean tableExists(DataSource dataSource, String table) {
        try {
            return inTransaction(dataSource, c -> {
                try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT 1 FROM " + table + " WHERE 1 = 0")) {
                    return true;
                }
            });
        } catch (SQLException ex) {
            // Normally "no such table"; anything else shows up again when creating it
            return false;
        }
    }

    private static Calendar utc() {
        return new GregorianCalendar(TimeZone.getTimeZone("UTC"), Locale.ROOT);
    }
}
