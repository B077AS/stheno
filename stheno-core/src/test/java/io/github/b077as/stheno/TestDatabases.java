package io.github.b077as.stheno;

import org.h2.jdbcx.JdbcDataSource;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;

/** H2 databases for the JDBC store tests. */
public final class TestDatabases {

    private TestDatabases() {
    }

    /** A new empty database; {@code settings} are appended to the URL, e.g. {@code ;IGNORECASE=TRUE}. */
    public static JdbcDataSource h2(String settings) {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1" + settings);
        return dataSource;
    }

    /**
     * The database as a node in another time zone sees it. H2 reads the JVM's zone only once, so the zone is set per connection, as
     * other drivers take it from the node's JVM.
     */
    public static DataSource inTimeZone(DataSource target, String zone) {
        return (DataSource) Proxy.newProxyInstance(TestDatabases.class.getClassLoader(), new Class<?>[] {DataSource.class},
                (proxy, method, args) -> {
                    Object result = method.invoke(target, args);
                    if (result instanceof Connection connection) {
                        try (Statement st = connection.createStatement()) {
                            st.execute("SET TIME ZONE '" + zone + "'");
                        }
                    }
                    return result;
                });
    }

    /**
     * Connections as a pool configured with auto-commit off hands them out (e.g. HikariCP with {@code autoCommit=false}): what was not
     * committed is rolled back when the connection is returned.
     */
    public static DataSource withoutAutoCommit(DataSource target) {
        return (DataSource) Proxy.newProxyInstance(TestDatabases.class.getClassLoader(), new Class<?>[] {DataSource.class},
                (proxy, method, args) -> {
                    if (!"getConnection".equals(method.getName())) {
                        return method.invoke(target, args);
                    }
                    Connection connection = (Connection) method.invoke(target, args);
                    connection.setAutoCommit(false);
                    return Proxy.newProxyInstance(TestDatabases.class.getClassLoader(), new Class<?>[] {Connection.class},
                            (p, m, a) -> {
                                if ("close".equals(m.getName()) && !connection.isClosed()) {
                                    connection.rollback();
                                }
                                try {
                                    return m.invoke(connection, a);
                                } catch (InvocationTargetException ex) {
                                    throw ex.getCause();
                                }
                            });
                });
    }
}
