package io.github.b077as.stheno.delivery;

import io.github.b077as.stheno.TestDatabases;

/**
 * The tracker's behaviour on a database that compares text without case (the default of MySQL, MariaDB and SQL Server): invoice
 * numbers stay case sensitive.
 */
class JdbcCaseInsensitiveDeliveryTrackerTest extends DeliveryTrackerTest {

    @Override
    DeliveryStore createStore() {
        JdbcDeliveryStore store = new JdbcDeliveryStore(TestDatabases.h2(";IGNORECASE=TRUE"), "stheno_sent_document");
        store.createTablesIfMissing();
        return store;
    }
}
