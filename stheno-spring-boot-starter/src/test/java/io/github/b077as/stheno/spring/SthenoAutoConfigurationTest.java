package io.github.b077as.stheno.spring;

import io.github.b077as.stheno.ap.AccessPointAcknowledger;
import io.github.b077as.stheno.ap.PhossApClient;
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
    static class OwnRouter {
        @Bean
        InboundRouter router() {
            return InboundRouter.builder().fallback(d -> InboundOutcome.accepted("NL")).build();
        }
    }
}
