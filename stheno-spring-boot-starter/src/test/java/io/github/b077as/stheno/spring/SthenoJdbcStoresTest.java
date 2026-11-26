package io.github.b077as.stheno.spring;

import io.github.b077as.stheno.delivery.DeliveryTracker;
import io.github.b077as.stheno.inbound.MessageKey;
import io.github.b077as.stheno.inbound.ReceivedMessageStore;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** How the JDBC stores live next to the application's own database access. */
class SthenoJdbcStoresTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SthenoAutoConfiguration.class))
            .withPropertyValues("stheno.inbound.store=jdbc", "stheno.delivery.enabled=true", "stheno.delivery.store=jdbc");

    @Test
    void theStoresDoNotJoinTheApplicationsTransaction() {
        runner.withUserConfiguration(TransactionAwareDataSource.class).run(ctx -> assertOwnTransactions(ctx.getBean(DataSource.class),
                ctx.getBean(ReceivedMessageStore.class), ctx.getBean(DeliveryTracker.class)));
    }

    @Test
    void alsoNotWhenTheTransactionAwareProxyIsWrappedAgain() {
        runner.withUserConfiguration(WrappedTransactionAwareDataSource.class).run(ctx -> assertOwnTransactions(
                ctx.getBean(DataSource.class), ctx.getBean(ReceivedMessageStore.class), ctx.getBean(DeliveryTracker.class)));
    }

    @Test
    void expiredMessagesArePurged() {
        runner.withUserConfiguration(TransactionAwareDataSource.class)
                .withPropertyValues("stheno.inbound.duplicate-retention=200ms")
                .run(ctx -> {
                    ctx.getBean(ReceivedMessageStore.class).register(new MessageKey("old", "iso6523-actorid-upis::0088:abc"));
                    JdbcTemplate jdbc = new JdbcTemplate(ctx.getBean(DataSource.class));
                    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
                    while (jdbc.queryForObject("SELECT COUNT(*) FROM stheno_received_message", Integer.class) > 0) {
                        assertThat(System.nanoTime()).as("purged in time").isLessThan(deadline);
                        Thread.sleep(20);
                    }
                });
    }

    /** What the application does in a transaction it rolls back must not undo what Stheno recorded meanwhile. */
    private static void assertOwnTransactions(DataSource dataSource, ReceivedMessageStore store, DeliveryTracker tracker) {
        MessageKey key = new MessageKey("in-a-transaction", "iso6523-actorid-upis::0088:abc");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE app_data (x INTEGER)");
        new TransactionTemplate(new DataSourceTransactionManager(dataSource)).executeWithoutResult(status -> {
            jdbc.update("INSERT INTO app_data VALUES (1)");
            store.register(key);
            tracker.track("sent-in-a-transaction", false);
            status.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_data", Integer.class)).isZero();
        assertThat(store.find(key)).isPresent();
        assertThat(tracker.find("sent-in-a-transaction")).isPresent();
    }

    private static DataSource h2() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        return ds;
    }

    @Configuration(proxyBeanMethods = false)
    static class TransactionAwareDataSource {
        @Bean
        DataSource dataSource() {
            return new TransactionAwareDataSourceProxy(h2());
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class WrappedTransactionAwareDataSource {
        @Bean
        DataSource dataSource() {
            return new LazyConnectionDataSourceProxy(new TransactionAwareDataSourceProxy(h2()));
        }
    }
}
