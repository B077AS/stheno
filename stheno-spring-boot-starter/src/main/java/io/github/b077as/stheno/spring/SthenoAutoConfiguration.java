package io.github.b077as.stheno.spring;

import io.github.b077as.stheno.ap.AccessPointAcknowledger;
import io.github.b077as.stheno.ap.PhossApClient;
import io.github.b077as.stheno.delivery.DeliveryListener;
import io.github.b077as.stheno.delivery.DeliveryStore;
import io.github.b077as.stheno.delivery.DeliveryTracker;
import io.github.b077as.stheno.delivery.InMemoryDeliveryStore;
import io.github.b077as.stheno.delivery.JdbcDeliveryStore;
import io.github.b077as.stheno.inbound.InMemoryReceivedMessageStore;
import io.github.b077as.stheno.inbound.InboundListener;
import io.github.b077as.stheno.inbound.InboundProcessor;
import io.github.b077as.stheno.inbound.InboundRouter;
import io.github.b077as.stheno.inbound.JdbcReceivedMessageStore;
import io.github.b077as.stheno.inbound.ReceivedMessageStore;
import io.github.b077as.stheno.inbound.UnhandledDocumentPolicy;
import io.github.b077as.stheno.lookup.CachingPeppolLookup;
import io.github.b077as.stheno.lookup.PeppolLookup;
import io.github.b077as.stheno.lookup.SmpPeppolLookup;
import io.github.b077as.stheno.spring.internal.DeliveryMonitor;
import io.github.b077as.stheno.spring.internal.HandlerMethodRegistrar;
import io.github.b077as.stheno.spring.internal.SpringEventDeliveryListener;
import io.github.b077as.stheno.spring.internal.SpringEventInboundListener;
import io.github.b077as.stheno.spring.internal.SthenoHealthIndicator;
import io.github.b077as.stheno.spring.internal.SthenoMetrics;
import io.github.b077as.stheno.spring.internal.StoreDataSources;
import io.github.b077as.stheno.spring.web.SthenoInboundController;
import io.github.b077as.stheno.validation.DocumentValidator;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Everything the starter adds. Each bean backs off when the application defines its own; optional features only switch on when their
 * libraries are on the classpath and their {@code stheno.*.enabled} property is set.
 */
@Slf4j
@AutoConfiguration(afterName = {
        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        "org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration",
        "org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration"})
@EnableConfigurationProperties(SthenoProperties.class)
public class SthenoAutoConfiguration {

    /** Name of an optional application {@link Executor} bean that runs the handlers (default: virtual threads). */
    public static final String HANDLER_EXECUTOR_BEAN = "sthenoInboundExecutor";

    /** Static, as a bean post-processor must be. */
    @Bean
    public static HandlerMethodRegistrar sthenoHandlerMethodRegistrar() {
        return new HandlerMethodRegistrar();
    }

    @Bean
    @ConditionalOnMissingBean
    public ReceivedMessageStore sthenoReceivedMessageStore(SthenoProperties properties, ObjectProvider<DataSource> dataSource) {
        SthenoProperties.Inbound cfg = properties.getInbound();
        if (cfg.getStore() == SthenoProperties.Store.JDBC) {
            DataSource ds = dataSource.getIfAvailable();
            if (ds == null) {
                throw new IllegalStateException("stheno.inbound.store=jdbc needs a DataSource bean");
            }
            JdbcReceivedMessageStore store = new JdbcReceivedMessageStore(StoreDataSources.withOwnConnections(ds), cfg.getJdbcTable(),
                    cfg.getDuplicateRetention(), cfg.getProcessingTimeout(), Clock.systemUTC());
            if (cfg.isJdbcCreateTable()) {
                store.createTableIfMissing();
            }
            return store;
        }
        return new InMemoryReceivedMessageStore(cfg.getDuplicateRetention());
    }

    @Bean
    public SpringEventInboundListener sthenoSpringEventInboundListener(ApplicationEventPublisher publisher) {
        return new SpringEventInboundListener(publisher);
    }

    @Bean
    @ConditionalOnMissingBean
    public InboundProcessor sthenoInboundProcessor(ReceivedMessageStore store, HandlerMethodRegistrar registrar,
                                                   ObjectProvider<InboundRouter> userRouter, List<InboundListener> listeners,
                                                   SthenoProperties properties, ObjectProvider<DeliveryTracker> tracker) {
        // Built on first use: by then every bean with handler methods exists
        InboundProcessor processor = new InboundProcessor(store, () -> {
            InboundRouter custom = userRouter.getIfAvailable();
            return custom != null ? custom : registrar.buildRouter();
        }, new ArrayList<>(listeners), properties.getInbound().getUnhandledDocuments());
        DeliveryTracker deliveries = tracker.getIfAvailable();
        return deliveries == null ? processor : processor.withDeliveryTracking(deliveries);
    }

    /** Builds the router once all beans exist, so that a wrong handler setup fails the start, not the first document. */
    @Bean
    public SmartInitializingSingleton sthenoRouterCheck(InboundProcessor processor, HandlerMethodRegistrar registrar,
                                                        SthenoProperties properties) {
        return () -> {
            processor.getRouter();
            if (registrar.getHandlerCount() == 0) {
                log.warn("Stheno: no @PeppolInboundHandler method found; without an own InboundRouter, received business documents"
                        + " are {}", properties.getInbound().getUnhandledDocuments() == UnhandledDocumentPolicy.REJECT
                        ? "rejected" : "not taken over (503)");
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "stheno.access-point", name = "base-url")
    public PhossApClient sthenoPhossApClient(SthenoProperties properties) {
        SthenoProperties.AccessPoint ap = properties.getAccessPoint();
        return new PhossApClient(ap.getBaseUrl(), ap.getToken(), ap.getTimeout());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(PhossApClient.class)
    public AccessPointAcknowledger sthenoAccessPointAcknowledger(PhossApClient client, SthenoProperties properties) {
        SthenoProperties.AccessPoint ap = properties.getAccessPoint();
        log.info("Stheno acknowledges to the Access Point at {}: receiver country {}, MLS verdict {}", ap.getBaseUrl(),
                ap.isReportReceiverCountry() ? "on" : "off", ap.isMlsVerdict() ? "on" : "off");
        return new AccessPointAcknowledger(client, ap.isReportReceiverCountry(), ap.isMlsVerdict());
    }

    @Bean
    @ConditionalOnMissingBean
    public SthenoInboundService sthenoInboundService(InboundProcessor processor, ObjectProvider<AccessPointAcknowledger> acknowledger,
                                                     SthenoProperties properties, ApplicationEventPublisher events,
                                                     org.springframework.beans.factory.BeanFactory beans) {
        Executor executor = beans.containsBean(HANDLER_EXECUTOR_BEAN) ? beans.getBean(HANDLER_EXECUTOR_BEAN, Executor.class) : null;
        return new SthenoInboundService(processor, acknowledger.getIfAvailable(), properties, events, executor);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(name = "org.springframework.web.servlet.DispatcherServlet")
    @ConditionalOnProperty(prefix = "stheno.inbound", name = "enabled", matchIfMissing = true)
    static class Web {

        @Bean
        @ConditionalOnMissingBean
        SthenoInboundController sthenoInboundController(SthenoInboundService service, SthenoProperties properties) {
            log.info("Stheno inbound endpoint at POST {} ({} forwarding, {} processing)", properties.getInbound().getPath(),
                    properties.getInbound().getForwardingMode(), properties.getInbound().getProcessing());
            return new SthenoInboundController(service, properties);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
    static class Metrics {

        @Bean
        @ConditionalOnBean(type = "io.micrometer.core.instrument.MeterRegistry")
        SthenoMetrics sthenoMetrics(MeterRegistry registry) {
            return new SthenoMetrics(registry);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.boot.health.contributor.AbstractHealthIndicator")
    static class Health {

        @Bean
        @ConditionalOnMissingBean(name = "sthenoHealthIndicator")
        SthenoHealthIndicator sthenoHealthIndicator(SthenoInboundService service, HandlerMethodRegistrar registrar, SthenoProperties properties) {
            return new SthenoHealthIndicator(service, registrar, properties);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "stheno.delivery", name = "enabled", havingValue = "true")
    static class DeliveryTracking {

        @Bean
        @ConditionalOnMissingBean
        DeliveryStore sthenoDeliveryStore(SthenoProperties properties, ObjectProvider<DataSource> dataSource) {
            SthenoProperties.Delivery cfg = properties.getDelivery();
            if (cfg.getStore() == SthenoProperties.Store.JDBC) {
                DataSource ds = dataSource.getIfAvailable();
                if (ds == null) {
                    throw new IllegalStateException("stheno.delivery.store=jdbc needs a DataSource bean");
                }
                JdbcDeliveryStore store = new JdbcDeliveryStore(StoreDataSources.withOwnConnections(ds), cfg.getJdbcTable());
                if (cfg.isJdbcCreateTable()) {
                    store.createTablesIfMissing();
                }
                return store;
            }
            log.warn("Stheno follows sent documents in memory only: their status is lost on restart (stheno.delivery.store=jdbc)");
            return new InMemoryDeliveryStore();
        }

        @Bean
        @ConditionalOnMissingBean
        DeliveryTracker sthenoDeliveryTracker(DeliveryStore store, SthenoProperties properties, ApplicationEventPublisher publisher,
                                              ObjectProvider<DeliveryListener> userListeners) {
            SthenoProperties.Delivery cfg = properties.getDelivery();
            if (cfg.getRetention().compareTo(cfg.getOverdueAfter()) <= 0) {
                throw new IllegalStateException("stheno.delivery.retention must be longer than stheno.delivery.overdue-after");
            }
            List<DeliveryListener> listeners = new ArrayList<>();
            listeners.add(new SpringEventDeliveryListener(publisher));
            userListeners.orderedStream().forEach(listeners::add);
            return new DeliveryTracker(store, listeners, cfg.getOverdueAfter(), Clock.systemUTC());
        }

        @Bean
        DeliveryMonitor sthenoDeliveryMonitor(DeliveryTracker tracker, DeliveryStore store, SthenoProperties properties) {
            SthenoProperties.Delivery cfg = properties.getDelivery();
            return new DeliveryMonitor(tracker, store, cfg.getRetention(), cfg.getCheckInterval());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "com.helger.smpclient.peppol.SMPClientReadOnly")
    @ConditionalOnProperty(prefix = "stheno.lookup", name = "enabled", havingValue = "true")
    static class Lookup {

        @Bean
        @ConditionalOnMissingBean
        PeppolLookup sthenoPeppolLookup(SthenoProperties properties) {
            SthenoProperties.Lookup cfg = properties.getLookup();
            SmpPeppolLookup smp = cfg.getSmpUrl() != null
                    ? SmpPeppolLookup.viaSmp(cfg.getSmpUrl(), network(cfg).getSmpTrustStore())
                    : SmpPeppolLookup.via(network(cfg));
            log.info("Stheno lookups in the Peppol {} network{}", cfg.getNetwork(), cfg.getSmpUrl() != null ? " at SMP " + cfg.getSmpUrl() : "");
            return new CachingPeppolLookup(smp, cfg.getPositiveTtl(), cfg.getNegativeTtl(), cfg.getMaxEntries());
        }

        private static io.github.b077as.stheno.lookup.PeppolNetwork network(SthenoProperties.Lookup cfg) {
            return cfg.getNetwork() == SthenoProperties.Network.TEST
                    ? io.github.b077as.stheno.lookup.PeppolNetwork.TEST
                    : io.github.b077as.stheno.lookup.PeppolNetwork.PRODUCTION;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = {"com.helger.phive.peppol.PeppolValidation", "com.helger.ddd.DocumentDetailsDeterminator"})
    @ConditionalOnProperty(prefix = "stheno.validation", name = "enabled", havingValue = "true")
    static class Validation {

        @Bean
        @ConditionalOnMissingBean
        DocumentValidator sthenoDocumentValidator() {
            return new DocumentValidator();
        }

        @Bean
        ApplicationListener<ApplicationReadyEvent> sthenoValidationWarmUp(DocumentValidator validator, SthenoProperties properties) {
            return event -> {
                List<String> ruleSets = properties.getValidation().getWarmUp();
                if (!ruleSets.isEmpty()) {
                    long start = System.nanoTime();
                    validator.warmUp(ruleSets);
                    log.info("Stheno prepared {} validation rule set(s) in {} ms", ruleSets.size(), (System.nanoTime() - start) / 1_000_000);
                }
            };
        }
    }
}
