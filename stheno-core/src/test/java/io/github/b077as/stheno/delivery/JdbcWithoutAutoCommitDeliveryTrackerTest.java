package io.github.b077as.stheno.delivery;

import io.github.b077as.stheno.TestDatabases;

/** The tracker's behaviour with a pool that hands out connections with auto-commit off. */
class JdbcWithoutAutoCommitDeliveryTrackerTest extends DeliveryTrackerTest {

    @Override
    DeliveryStore createStore() {
        JdbcDeliveryStore store = new JdbcDeliveryStore(TestDatabases.withoutAutoCommit(TestDatabases.h2("")), "stheno_sent_document");
        store.createTablesIfMissing();
        store.createTablesIfMissing();
        return store;
    }
}
