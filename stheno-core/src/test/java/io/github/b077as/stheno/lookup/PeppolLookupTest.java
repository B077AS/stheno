package io.github.b077as.stheno.lookup;

import com.helger.peppol.smp.ESMPTransportProfile;
import com.helger.xsds.peppol.smp1.EndpointType;
import com.helger.xsds.peppol.id1.ProcessIdentifierType;
import com.helger.xsds.peppol.smp1.ProcessListType;
import com.helger.xsds.peppol.smp1.ProcessType;
import com.helger.xsds.peppol.smp1.ServiceEndpointList;
import com.helger.xsds.peppol.smp1.ServiceInformationType;
import com.helger.xsds.peppol.smp1.ServiceMetadataType;
import io.github.b077as.stheno.SthenoException;
import io.github.b077as.stheno.TestDocs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PeppolLookupTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-15T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void canReceive() {
        SmpPeppolLookup lookup = new SmpPeppolLookup((p, d) -> metadata(TestDocs.BILLING_PROCESS,
                ESMPTransportProfile.TRANSPORT_PROFILE_PEPPOL_AS4_V2.getID()), CLOCK);
        ReceiverCapability c = lookup.lookup(TestDocs.RECEIVER, TestDocs.INVOICE_DOCTYPE, TestDocs.BILLING_PROCESS);
        assertThat(c.canReceive()).isTrue();
        assertThat(c.getEndpointUrl()).isEqualTo("https://ap.example.com/as4");
        assertThat(c.getParticipantId()).isEqualTo("iso6523-actorid-upis::" + TestDocs.RECEIVER);
        assertThat(c.getCheckedAt()).isEqualTo(CLOCK.instant());
    }

    @Test
    void otherProcessOrTransportIsNotSupported() {
        SmpPeppolLookup otherProcess = new SmpPeppolLookup((p, d) -> metadata("urn:other:process",
                ESMPTransportProfile.TRANSPORT_PROFILE_PEPPOL_AS4_V2.getID()), CLOCK);
        assertThat(otherProcess.lookup(TestDocs.RECEIVER, TestDocs.INVOICE_DOCTYPE, TestDocs.BILLING_PROCESS).getStatus())
                .isEqualTo(ReceiverCapability.Status.PROCESS_NOT_SUPPORTED);
        SmpPeppolLookup as2 = new SmpPeppolLookup((p, d) -> metadata(TestDocs.BILLING_PROCESS,
                ESMPTransportProfile.TRANSPORT_PROFILE_AS2_V2.getID()), CLOCK);
        assertThat(as2.lookup(TestDocs.RECEIVER, TestDocs.INVOICE_DOCTYPE, TestDocs.BILLING_PROCESS).getStatus())
                .isEqualTo(ReceiverCapability.Status.PROCESS_NOT_SUPPORTED);
    }

    @Test
    void documentTypeNotSupportedAndNotRegistered() {
        assertThat(new SmpPeppolLookup((p, d) -> null, CLOCK)
                .lookup(TestDocs.RECEIVER, TestDocs.INVOICE_DOCTYPE, TestDocs.BILLING_PROCESS).getStatus())
                .isEqualTo(ReceiverCapability.Status.DOCUMENT_TYPE_NOT_SUPPORTED);
        SmpPeppolLookup unknown = new SmpPeppolLookup((p, d) -> {
            throw new SmpPeppolLookup.NotRegisteredException("no DNS entry", null);
        }, CLOCK);
        assertThat(unknown.lookup(TestDocs.RECEIVER, TestDocs.INVOICE_DOCTYPE, TestDocs.BILLING_PROCESS).getStatus())
                .isEqualTo(ReceiverCapability.Status.NOT_REGISTERED);
    }

    @Test
    void networkErrorsAreNotAnswers() {
        SmpPeppolLookup broken = new SmpPeppolLookup((p, d) -> {
            throw new java.io.IOException("connection reset");
        }, CLOCK);
        assertThatThrownBy(() -> broken.lookup(TestDocs.RECEIVER, TestDocs.INVOICE_DOCTYPE, TestDocs.BILLING_PROCESS))
                .isInstanceOf(SthenoException.class)
                .satisfies(ex -> assertThat(((SthenoException) ex).getCode()).isEqualTo(SthenoException.ErrorCode.LOOKUP_FAILED));
        assertThatThrownBy(() -> broken.lookup("garbage", TestDocs.INVOICE_DOCTYPE, TestDocs.BILLING_PROCESS))
                .satisfies(ex -> assertThat(((SthenoException) ex).getCode()).isEqualTo(SthenoException.ErrorCode.INVALID_INPUT));
    }

    @Test
    void cacheKeepsAnswersForTheirLifetimeAndIgnoresParticipantCase() {
        AtomicInteger calls = new AtomicInteger();
        MutableClock clock = new MutableClock(CLOCK.instant());
        PeppolLookup counting = (p, d, pr) -> {
            calls.incrementAndGet();
            return new ReceiverCapability(p, d, pr, ReceiverCapability.Status.NOT_REGISTERED, null, clock.instant());
        };
        CachingPeppolLookup cache = new CachingPeppolLookup(counting, Duration.ofHours(24), Duration.ofMinutes(10), 100, clock);
        cache.lookup("0088:ABC", TestDocs.INVOICE_DOCTYPE, TestDocs.BILLING_PROCESS);
        cache.lookup("0088:abc", TestDocs.INVOICE_DOCTYPE, TestDocs.BILLING_PROCESS);
        assertThat(calls).hasValue(1);
        clock.now = clock.now.plus(Duration.ofMinutes(11));
        cache.lookup("0088:abc", TestDocs.INVOICE_DOCTYPE, TestDocs.BILLING_PROCESS);
        assertThat(calls).hasValue(2);
        cache.clear();
        cache.lookup("0088:abc", TestDocs.INVOICE_DOCTYPE, TestDocs.BILLING_PROCESS);
        assertThat(calls).hasValue(3);
    }

    /** Real network: {@code mvn test -DargLine="-Dstheno.live=true"}. Austrian government test participant on the Peppol test SML. */
    @Test
    @EnabledIfSystemProperty(named = "stheno.live", matches = "true")
    void liveLookupOnThePeppolTestNetwork() {
        ReceiverCapability c = SmpPeppolLookup.via(PeppolNetwork.TEST)
                .lookup("9915:test", TestDocs.INVOICE_DOCTYPE, TestDocs.BILLING_PROCESS);
        assertThat(c.canReceive()).as(c.toString()).isTrue();
        assertThat(SmpPeppolLookup.via(PeppolNetwork.TEST)
                .lookup("9915:does-not-exist-stheno-test", TestDocs.INVOICE_DOCTYPE, TestDocs.BILLING_PROCESS).getStatus())
                .isEqualTo(ReceiverCapability.Status.NOT_REGISTERED);
    }

    private static ServiceMetadataType metadata(String processValue, String transportProfile) {
        EndpointType endpoint = new EndpointType();
        endpoint.setTransportProfile(transportProfile);
        endpoint.setEndpointReference(com.helger.smpclient.peppol.utils.W3CEndpointReferenceHelper.createEndpointReference("https://ap.example.com/as4"));
        ServiceEndpointList endpoints = new ServiceEndpointList();
        endpoints.addEndpoint(endpoint);
        ProcessIdentifierType pid = new ProcessIdentifierType();
        pid.setScheme("cenbii-procid-ubl");
        pid.setValue(processValue);
        ProcessType process = new ProcessType();
        process.setProcessIdentifier(pid);
        process.setServiceEndpointList(endpoints);
        ProcessListType processes = new ProcessListType();
        processes.addProcess(process);
        ServiceInformationType info = new ServiceInformationType();
        info.setProcessList(processes);
        ServiceMetadataType md = new ServiceMetadataType();
        md.setServiceInformation(info);
        return md;
    }

    private static final class MutableClock extends Clock {
        Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
