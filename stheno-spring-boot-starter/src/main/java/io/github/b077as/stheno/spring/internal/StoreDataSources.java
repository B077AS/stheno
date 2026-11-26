package io.github.b077as.stheno.spring.internal;

import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.util.ClassUtils;

import javax.sql.DataSource;

/**
 * The data source Stheno's JDBC stores use. They commit their own transactions, so they must not get the connection of a transaction
 * the application is in: Spring's {@link TransactionAwareDataSourceProxy} (also inside other delegating data sources) is bypassed.
 */
public final class StoreDataSources {

    private StoreDataSources() {
    }

    public static DataSource withOwnConnections(DataSource dataSource) {
        // spring-jdbc is optional: its classes are only touched when it is there
        return ClassUtils.isPresent("org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy",
                StoreDataSources.class.getClassLoader()) ? SpringJdbc.unwrap(dataSource) : dataSource;
    }

    private static final class SpringJdbc {

        static DataSource unwrap(DataSource dataSource) {
            DataSource current = dataSource;
            while (current instanceof DelegatingDataSource delegating && delegating.getTargetDataSource() != null) {
                if (delegating instanceof TransactionAwareDataSourceProxy) {
                    return unwrap(delegating.getTargetDataSource());
                }
                current = delegating.getTargetDataSource();
            }
            return dataSource;
        }
    }
}
