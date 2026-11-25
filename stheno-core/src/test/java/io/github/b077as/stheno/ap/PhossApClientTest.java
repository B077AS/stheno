package io.github.b077as.stheno.ap;

import com.sun.net.httpserver.HttpServer;
import io.github.b077as.stheno.SthenoException;
import io.github.b077as.stheno.TestDocs;
import io.github.b077as.stheno.inbound.DeliveryMetadata;
import io.github.b077as.stheno.inbound.HandlingResult;
import io.github.b077as.stheno.inbound.InMemoryReceivedMessageStore;
import io.github.b077as.stheno.inbound.InboundOutcome;
import io.github.b077as.stheno.inbound.InboundProcessor;
import io.github.b077as.stheno.inbound.InboundRouter;
import io.github.b077as.stheno.inbound.MlsIssue;
import io.github.b077as.stheno.inbound.VerificationResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PhossApClientTest {

    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger reportStatus = new AtomicInteger(200);
    private final AtomicInteger mlsStatus = new AtomicInteger(200);
    private PhossApClient client;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI() + " token=" + exchange.getRequestHeaders().getFirst("X-Token")
                    + " body=" + body);
            int status = exchange.getRequestURI().getPath().endsWith("/report") ? reportStatus.get() : mlsStatus.get();
            byte[] answer = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, answer.length);
            exchange.getResponseBody().write(answer);
            exchange.close();
        });
        server.start();
        client = new PhossApClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"), "secret", Duration.ofSeconds(5));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void reportsTheReceiverCountry() {
        assertThat(client.reportReceiverCountry("a b&c", "DK")).isEqualTo(ApCallResult.DONE);
        assertThat(requests).singleElement().asString()
                .startsWith("POST /api/inbound/report?sbdhInstanceID=a+b%26c&c4CountryCode=DK token=secret");
    }

    @Test
    void countryAlreadyKnownIsNotAnError() {
        reportStatus.set(400);
        assertThat(client.reportReceiverCountry("id", "DK")).isEqualTo(ApCallResult.ALREADY_DONE);
    }

    @Test
    void unknownTransactionIsAnError() {
        reportStatus.set(404);
        assertThatThrownBy(() -> client.reportReceiverCountry("id", "DK"))
                .isInstanceOf(SthenoException.class)
                .hasMessageContaining("HTTP 404");
    }

    @Test
    void sendsTheMlsVerdictWithIssues() {
        InboundOutcome rejected = InboundOutcome.rejected("Invalid element \"X\"",
                List.of(MlsIssue.businessRule("cac:AccountingCustomerParty", "BR-07 violated")));
        assertThat(client.sendMlsVerdict("id-1", rejected)).isEqualTo(ApCallResult.DONE);
        assertThat(requests).singleElement().asString().contains("POST /api/mls/send token=secret body="
                + "{\"sbdhInstanceID\":\"id-1\",\"responseCode\":\"RE\",\"responseText\":\"Invalid element \\\"X\\\"\","
                + "\"issues\":[{\"statusReasonCode\":\"BV\",\"errorField\":\"cac:AccountingCustomerParty\","
                + "\"description\":\"BR-07 violated\"}]}");
    }

    @Test
    void mlsStatusCodesAreMapped() {
        mlsStatus.set(409);
        assertThat(client.sendMlsVerdict("id", InboundOutcome.accepted(null))).isEqualTo(ApCallResult.ALREADY_DONE);
        mlsStatus.set(503);
        assertThat(client.sendMlsVerdict("id", InboundOutcome.accepted(null))).isEqualTo(ApCallResult.NOT_APPLICABLE);
        mlsStatus.set(422);
        assertThat(client.sendMlsVerdict("id", InboundOutcome.accepted(null))).isEqualTo(ApCallResult.NOT_APPLICABLE);
        mlsStatus.set(401);
        assertThatThrownBy(() -> client.sendMlsVerdict("id", InboundOutcome.accepted(null))).hasMessageContaining("HTTP 401");
    }

    @Test
    void positiveVerdictJsonHasNoIssues() {
        assertThat(PhossApClient.mlsRequestJson("x", InboundOutcome.accepted("BE")))
                .isEqualTo("{\"sbdhInstanceID\":\"x\",\"responseCode\":\"AP\"}");
    }

    @Test
    void acknowledgerHonoursTheSwitches() {
        HandlingResult result = handled(InboundOutcome.accepted("DK"));

        Acknowledgement both = new AccessPointAcknowledger(client, true, true).acknowledge(result, false);
        assertThat(both.getReceiverCountry()).isEqualTo(ApCallResult.DONE);
        assertThat(both.getMlsVerdict()).isEqualTo(ApCallResult.DONE);
        assertThat(requests).hasSize(2);

        requests.clear();
        Acknowledgement off = new AccessPointAcknowledger(client, false, false).acknowledge(result, false);
        assertThat(off).isEqualTo(Acknowledgement.NOTHING);
        assertThat(requests).isEmpty();

        Acknowledgement countryAlreadySent = new AccessPointAcknowledger(client, true, true).acknowledge(result, true);
        assertThat(countryAlreadySent.getReceiverCountry()).isEqualTo(ApCallResult.SKIPPED);
        assertThat(requests).singleElement().asString().contains("/api/mls/send");
    }

    @Test
    void rejectionReportsNoCountryAndFailuresAreNotThrown() {
        mlsStatus.set(500);
        HandlingResult rejected = handled(InboundOutcome.rejected(MlsIssue.businessRule(null, "bad")));
        Acknowledgement ack = new AccessPointAcknowledger(client, true, true).acknowledge(rejected, false);
        assertThat(ack.getReceiverCountry()).isEqualTo(ApCallResult.SKIPPED);
        assertThat(ack.getMlsVerdict()).isEqualTo(ApCallResult.FAILED);
        assertThat(ack.isFailed()).isTrue();
    }

    @Test
    void rejectionTheAccessPointDoesNotTakeIsReported() {
        AccessPointAcknowledger acknowledger = new AccessPointAcknowledger(client, true, true);
        HandlingResult rejected = handled(InboundOutcome.rejected(MlsIssue.businessRule(null, "bad")));

        assertThat(acknowledger.acknowledge(rejected, false).isRejectionNotDelivered()).isFalse();
        mlsStatus.set(409);
        assertThat(acknowledger.acknowledge(rejected, false).isRejectionNotDelivered()).isTrue();
        mlsStatus.set(503);
        assertThat(acknowledger.acknowledge(rejected, false).isRejectionNotDelivered()).isTrue();
        assertThat(new AccessPointAcknowledger(client, true, false).acknowledge(rejected, false).isRejectionNotDelivered()).isTrue();

        mlsStatus.set(409);
        assertThat(acknowledger.acknowledge(handled(InboundOutcome.accepted(null)), false).isRejectionNotDelivered()).isFalse();
    }

    @Test
    void retryRepeatsOnlyTheFailedCall() {
        AccessPointAcknowledger acknowledger = new AccessPointAcknowledger(client, true, true);
        HandlingResult accepted = handled(InboundOutcome.accepted("DK"));
        reportStatus.set(500);
        Acknowledgement first = acknowledger.acknowledge(accepted, false);
        assertThat(first.getReceiverCountry()).isEqualTo(ApCallResult.FAILED);

        requests.clear();
        reportStatus.set(200);
        Acknowledgement second = acknowledger.acknowledge(accepted, false, first);
        assertThat(second.getReceiverCountry()).isEqualTo(ApCallResult.DONE);
        assertThat(second.getMlsVerdict()).isEqualTo(ApCallResult.DONE);
        assertThat(requests).singleElement().asString().contains("/api/inbound/report");
    }

    @Test
    void receivedMlsIsNeverAcknowledged() {
        InboundProcessor processor = new InboundProcessor(new InMemoryReceivedMessageStore(Duration.ofHours(1)),
                InboundRouter.builder().build(), List.of());
        HandlingResult mls = processor.process(TestDocs.mlsEnvelope("x", false).getXml(), null);
        assertThat(new AccessPointAcknowledger(client, true, true).acknowledge(mls, false)).isEqualTo(Acknowledgement.NOTHING);
        assertThat(requests).isEmpty();
    }

    @Test
    void forwardingHeadersAreRead() {
        String details = Base64.getEncoder().encodeToString("[{\"code\":\"BR-01\"}]".getBytes(StandardCharsets.UTF_8));
        DeliveryMetadata meta = PhossApForwarding.metadataFromHeaders(Map.of(
                "x-sbdh-instance-id", "abc",
                "X-phoss-AP-Transaction-ID", "tx-9",
                "X-Verification-Result", "rejected",
                "X-Verification-Details", details,
                "X-Verification-Details-Truncated", "true"));
        assertThat(meta.getSource()).isEqualTo(PhossApForwarding.SOURCE);
        assertThat(meta.getAnnouncedInstanceIdentifier()).isEqualTo("abc");
        assertThat(meta.getAccessPointTransactionId()).isEqualTo("tx-9");
        assertThat(meta.getVerificationResult()).isEqualTo(VerificationResult.REJECTED);
        assertThat(meta.getVerificationDetailsJson()).isEqualTo("[{\"code\":\"BR-01\"}]");
        assertThat(meta.isVerificationDetailsTruncated()).isTrue();

        DeliveryMetadata broken = PhossApForwarding.metadataFromHeaders(Map.of("X-Verification-Details", "%%%"));
        assertThat(broken.getVerificationDetailsJson()).isNull();
        assertThat(broken.getVerificationResult()).isEqualTo(VerificationResult.NONE);
    }

    @Test
    void syncAnswers() {
        assertThat(PhossApForwarding.syncReceived("AT")).isEqualTo("{\"countryCodeC4\":\"AT\",\"status\":\"received\"}");
        assertThat(PhossApForwarding.syncReceived(null)).isEqualTo("{\"status\":\"received\"}");
        assertThat(PhossApForwarding.syncRejectedNoRetry("dup\nline")).isEqualTo("{\"retry\":\"none\",\"errorMessage\":\"dup\\nline\"}");
    }

    private static HandlingResult handled(InboundOutcome outcome) {
        InboundProcessor processor = new InboundProcessor(new InMemoryReceivedMessageStore(Duration.ofHours(1)),
                InboundRouter.builder().fallback(d -> outcome).build(), List.of());
        return processor.process(TestDocs.invoiceEnvelope(java.util.UUID.randomUUID().toString()).getXml(), null);
    }
}
