package io.github.b077as.stheno.delivery;

class InMemoryDeliveryTrackerTest extends DeliveryTrackerTest {

    @Override
    DeliveryStore createStore() {
        return new InMemoryDeliveryStore();
    }
}
