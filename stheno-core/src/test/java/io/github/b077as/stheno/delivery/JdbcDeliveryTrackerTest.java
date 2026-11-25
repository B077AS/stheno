package io.github.b077as.stheno.delivery;

import org.h2.jdbcx.JdbcDataSource;

import java.util.UUID;

class JdbcDeliveryTrackerTest extends DeliveryTrackerTest {

    @Override
    DeliveryStore createStore() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        JdbcDeliveryStore store = new JdbcDeliveryStore(dataSource, "stheno_sent_document");
        store.createTablesIfMissing();
        store.createTablesIfMissing();
        return store;
    }
}
