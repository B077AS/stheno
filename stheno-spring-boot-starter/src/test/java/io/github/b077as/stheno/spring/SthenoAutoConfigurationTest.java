package io.github.b077as.stheno.spring;

import io.github.b077as.stheno.ap.AccessPointAcknowledger;
import io.github.b077as.stheno.ap.PhossApClient;
import io.github.b077as.stheno.delivery.DeliveryStore;
import io.github.b077as.stheno.delivery.DeliveryTracker;
import io.github.b077as.stheno.delivery.JdbcDeliveryStore;
import io.github.b077as.stheno.inbound.InboundPayload;
import io.github.b077as.stheno.reply.InvoiceResponse;
import io.github.b077as.stheno.reply.InvoiceResponseBuilder;
import io.github.b077as.stheno.spring.event.SthenoEvents;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import io.github.b077as.stheno.inbound.InMemoryReceivedMessageStore;
import io.github.b077as.stheno.inbound.InboundDocument;
import io.github.b077as.stheno.inbound.InboundOutcome;
import io.github.b077as.stheno.inbound.InboundProcessor;
import io.github.b077as.stheno.inbound.InboundRouter;
import io.github.b077as.stheno.inbound.JdbcReceivedMessageStore;
import io.github.b077as.stheno.inbound.ReceivedMessageStore;
import io.github.b077as.stheno.inbound.ReceivedMls;
import io.github.b077as.stheno.lookup.CachingPeppolLookup;
import io.github.b077as.stheno.lookup.PeppolLookup;
import io.github.b077as.stheno.validation.DocumentValidator;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SthenoAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SthenoAutoConfiguration.class));

    @Test
    void defaultsWithoutAccessPoint() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(InboundProcessor.class);
            assertThat(ctx).hasSingleBean(SthenoInboundService.class);
            assertThat(ctx.getBean(ReceivedMessageStore.class)).isInstanceOf(InMemoryReceivedMessageStore.class);
            assertThat(ctx).doesNotHaveBean(PhossApClient.class);
            assertThat(ctx).doesNotHaveBean(AccessPointAcknowledger.class);
            assertThat(ctx).doesNotHaveBean(PeppolLookup.class);
            assertThat(ctx).doesNotHaveBean(DocumentValidator.class);
            assertThat(ctx.getBean(SthenoInboundService.class).isAcknowledging()).isFalse();
        });
    }

    @Test
    void accessPointSwitches() {
        runner.withPropertyValues("stheno.access-point.base-url=http://localhost:1", "stheno.access-point.mls-verdict=false")
                .run(ctx -> {
                    AccessPointAcknowledger ack = ctx.getBean(AccessPointAcknowledger.class);
                    assertThat(ack.isReportReceiverCountry()).isTrue();
                    assertThat(ack.isSendMlsVerdict()).isFalse();
                    assertThat(ctx.getBean(SthenoInboundService.class).isAcknowledging()).isTrue();
                });
    }

    @Test
    void lookupWhenEnabled() {
        runner.withPropertyValues("stheno.lookup.enabled=true", "stheno.lookup.network=test")
                .run(ctx -> assertThat(ctx.getBean(PeppolLookup.class)).isInstanceOf(CachingPeppolLookup.class));
    }

    @Test
    void validationWhenEnabled() {
        runner.withPropertyValues("stheno.validation.enabled=true")
                .run(ctx -> assertThat(ctx).hasSingleBean(DocumentValidator.class));
    }

    @Test
    void jdbcStoreWithDataSource() {
        runner.withUserConfiguration(DataSourceConfig.class)
                .withPropertyValues("stheno.inbound.store=jdbc")
                .run(ctx -> assertThat(ctx.getBean(ReceivedMessageStore.class)).isInstanceOf(JdbcReceivedMessageStore.class));
    }

    @Test
    void jdbcStoreWithoutDataSourceFailsClearly() {
        runner.withPropertyValues("stheno.inbound.store=jdbc")
                .run(ctx -> assertThat(ctx).getFailure().hasStackTraceContaining("stheno.inbound.store=jdbc needs a DataSource bean"));
    }

    @Test
    void deliveryTrackingIsOffByDefault() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(DeliveryTracker.class));
    }

    @Test
    void deliveryTrackingWithJdbc() {
        runner.withUserConfiguration(DataSourceConfig.class)
                .withPropertyValues("stheno.delivery.enabled=true", "stheno.delivery.store=jdbc")
                .run(ctx -> {
                    assertThat(ctx.getBean(DeliveryStore.class)).isInstanceOf(JdbcDeliveryStore.class);
                    ctx.getBean(DeliveryTracker.class).track("sent-1", false);
                    assertThat(ctx.getBean(DeliveryTracker.class).find("sent-1")).isPresent();
                });
    }

    @Test
    void overdueDocumentsArePublishedAsEvents() {
        List<Object> events = new CopyOnWriteArrayList<>();
        runner.withInitializer(c -> c.addApplicationListener((ApplicationListener<PayloadApplicationEvent<?>>) e ->
                        events.add(e.getPayload())))
                .withPropertyValues("stheno.delivery.enabled=true", "stheno.delivery.overdue-after=50ms",
                        "stheno.delivery.check-interval=100ms")
                .run(ctx -> {
                    ctx.getBean(DeliveryTracker.class).track("sent-1", true);
                    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
                    while (events.stream().noneMatch(e -> e instanceof SthenoEvents.DeliveryOverdue)) {
                        assertThat(System.nanoTime()).as("overdue event in time").isLessThan(deadline);
                        Thread.onSpinWait();
                    }
                });
    }

    @Test
    void retentionShorterThanTheOverdueTimeFailsClearly() {
        runner.withPropertyValues("stheno.delivery.enabled=true", "stheno.delivery.retention=1h")
                .run(ctx -> assertThat(ctx).getFailure().hasStackTraceContaining("must be longer than"));
    }

    @Test
    void annotatedHandlersAreRouted() {
        runner.withUserConfiguration(GoodHandlers.class).run(ctx -> {
            InboundRouter router = ctx.getBean(InboundProcessor.class).getRouter();
            InboundDocument invoice = InboundDocument.builder().documentTypeId("busdox-docid-qns::urn:x:invoice").build();
            InboundDocument other = InboundDocument.builder().documentTypeId("busdox-docid-qns::urn:x:other").build();
            assertThat(router.findHandler(invoice).handle(invoice).getReceiverCountry()).isEqualTo("IT");
            assertThat(router.findHandler(other).handle(other).getReceiverCountry()).isEqualTo("FR");
            assertThat(router.getMlsHandler()).isNotNull();
        });
    }

    @Test
    void wrongHandlerSignatureFailsTheStart() {
        runner.withUserConfiguration(WrongSignature.class)
                .run(ctx -> assertThat(ctx).getFailure().hasStackTraceContaining("must have the signature 'InboundOutcome name(InboundDocument document)'"));
    }

    @Test
    void twoFallbacksFailTheStart() {
        runner.withUserConfiguration(TwoFallbacks.class)
                .run(ctx -> assertThat(ctx).getFailure().hasStackTraceContaining("More than one @PeppolInboundHandler without documentType"));
    }

    @Test
    void invoiceResponsesGoToTheirHandlerBeforeTheFallback() {
        runner.withUserConfiguration(ResponseHandlers.class, GoodHandlers.class).run(ctx -> {
            InboundRouter router = ctx.getBean(InboundProcessor.class).getRouter();
            InboundDocument response = InboundDocument.builder()
                    .documentTypeId("busdox-docid-qns::" + InvoiceResponseBuilder.DOCUMENT_TYPE_ID)
                    .payload(InboundPayload.xml(InvoiceResponseBuilder.create().id("r").sender("0088:abc", "B")
                            .receiver("0088:def", "S").invoice("1", "380", null).status(InvoiceResponseBuilder.Status.AP).build()))
                    .build();
            assertThat(router.findHandler(response).handle(response).getReceiverCountry()).isEqualTo("SE");
        });
    }

    @Test
    void wrongResponseHandlerSignatureFailsTheStart() {
        runner.withUserConfiguration(WrongResponseSignature.class)
                .run(ctx -> assertThat(ctx).getFailure().hasStackTraceContaining("InboundOutcome name(InvoiceResponse response)"));
    }

    @Test
    void twoHandlersForInvoiceResponsesFailTheStart() {
        runner.withUserConfiguration(ResponseHandlers.class, TypedResponseHandler.class)
                .run(ctx -> assertThat(ctx).getFailure().hasStackTraceContaining("Invoice Responses would have two handlers"));
    }

    @Test
    void ownRouterBeanWins() {
        runner.withUserConfiguration(OwnRouter.class, GoodHandlers.class).run(ctx -> {
            InboundDocument any = InboundDocument.builder().documentTypeId("busdox-docid-qns::urn:x:invoice").build();
            assertThat(ctx.getBean(InboundProcessor.class).getRouter().findHandler(any).handle(any).getReceiverCountry()).isEqualTo("NL");
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class DataSourceConfig {
        @Bean
        DataSource dataSource() {
            JdbcDataSource ds = new JdbcDataSource();
            ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
            return ds;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class GoodHandlers {
        @Bean
        Handlers handlers() {
            return new Handlers();
        }
    }

    static class Handlers {
        @PeppolInboundHandler(documentType = "urn:x:invoice")
        InboundOutcome invoice(InboundDocument d) {
            return InboundOutcome.accepted("IT");
        }

        @PeppolInboundHandler
        InboundOutcome anything(InboundDocument d) {
            return InboundOutcome.accepted("FR");
        }

        @PeppolMlsHandler
        void mls(ReceivedMls mls, InboundDocument d) {
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class WrongSignature {
        @Bean
        Object wrong() {
            return new Object() {
                @PeppolInboundHandler
                public void handle(String s) {
                }
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class TwoFallbacks {
        @Bean
        Handlers first() {
            return new Handlers();
        }

        @Bean
        Handlers second() {
            return new Handlers();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ResponseHandlers {
        @Bean
        Object responses() {
            return new Object() {
                @PeppolInvoiceResponseHandler
                public InboundOutcome response(InvoiceResponse response) {
                    return InboundOutcome.accepted("SE");
                }
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class TypedResponseHandler {
        @Bean
        Object typed() {
            return new Object() {
                @PeppolInboundHandler(documentType = InvoiceResponseBuilder.DOCUMENT_TYPE_ID)
                public InboundOutcome response(InboundDocument document) {
                    return InboundOutcome.accepted("NO");
                }
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class WrongResponseSignature {
        @Bean
        Object wrongResponse() {
            return new Object() {
                @PeppolInvoiceResponseHandler
                public void response(InvoiceResponse response) {
                }
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class OwnRouter {
        @Bean
        InboundRouter router() {
            return InboundRouter.builder().fallback(d -> InboundOutcome.accepted("NL")).build();
        }
    }
}
