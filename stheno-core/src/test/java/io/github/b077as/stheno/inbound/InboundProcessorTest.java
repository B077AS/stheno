package io.github.b077as.stheno.inbound;

import io.github.b077as.stheno.TestDocs;
import io.github.b077as.stheno.envelope.BuiltEnvelope;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class InboundProcessorTest {

    private final InMemoryReceivedMessageStore store = new InMemoryReceivedMessageStore(Duration.ofDays(7));
    private final List<String> events = new ArrayList<>();
    private final InboundListener recorder = new InboundListener() {
        @Override
        public void onReceived(InboundDocument document) {
            events.add("received");
        }

        @Override
        public void onDuplicate(Reception reception) {
            events.add("duplicate");
        }

        @Override
        public void onHandled(HandlingResult result) {
            events.add("handled");
        }

        @Override
        public void onHandlerFailed(HandlingResult result) {
            events.add("failed");
        }
    };

    @Test
    void handlesAndRemembersTheOutcome() {
        AtomicInteger calls = new AtomicInteger();
        InboundProcessor processor = processor(InboundRouter.builder()
                .documentType(TestDocs.INVOICE_DOCTYPE, doc -> {
                    calls.incrementAndGet();
                    return InboundOutcome.accepted("DK");
                })
                .build());

        BuiltEnvelope env = TestDocs.invoiceEnvelope("11111111-1111-1111-1111-111111111111");
        HandlingResult first = processor.process(env.getXml(), DeliveryMetadata.of("test"));
        assertThat(first.getStatus()).isEqualTo(HandlingResult.Status.HANDLED);
        assertThat(first.getOutcome().getMlsResponseCode()).isEqualTo("AP");
        assertThat(first.isAcknowledgeable()).isTrue();

        HandlingResult second = processor.process(env.getXml(), DeliveryMetadata.of("test"));
        assertThat(second.getStatus()).isEqualTo(HandlingResult.Status.DUPLICATE);
        assertThat(second.getOutcome().getReceiverCountry()).isEqualTo("DK");
        assertThat(second.isAcknowledgeable()).isFalse();
        assertThat(calls).hasValue(1);
        assertThat(events).containsExactly("received", "handled", "duplicate");
    }

    @Test
    void failedHandlerReleasesTheMessageForANewDelivery() {
        AtomicInteger calls = new AtomicInteger();
        InboundProcessor processor = processor(InboundRouter.builder()
                .fallback(doc -> {
                    if (calls.incrementAndGet() == 1) {
                        throw new IllegalStateException("database down");
                    }
                    return InboundOutcome.accepted(null);
                })
                .build());

        BuiltEnvelope env = TestDocs.invoiceEnvelope("22222222-2222-2222-2222-222222222222");
        HandlingResult failed = processor.process(env.getXml(), null);
        assertThat(failed.getStatus()).isEqualTo(HandlingResult.Status.FAILED);
        assertThat(failed.getFailure()).hasMessage("database down");
        assertThat(failed.isAcknowledgeable()).isFalse();

        HandlingResult retried = processor.process(env.getXml(), null);
        assertThat(retried.getStatus()).isEqualTo(HandlingResult.Status.HANDLED);
        assertThat(calls).hasValue(2);
        assertThat(events).containsExactly("received", "failed", "received", "handled");
    }

    @Test
    void documentWithoutHandlerIsNotTakenOver() {
        InboundProcessor processor = processor(InboundRouter.builder().build());
        io.github.b077as.stheno.envelope.BuiltEnvelope env = TestDocs.invoiceEnvelope("77777777-7777-7777-7777-777777777777");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> processor.receive(env.getXml(), null))
                .isInstanceOf(io.github.b077as.stheno.SthenoException.class)
                .hasMessageContaining("No handler");
        assertThat(store.size()).isZero();
        assertThat(events).isEmpty();
    }

    @Test
    void onlyWarningsCannotRejectADocument() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> InboundOutcome.rejected("warnings", List.of(MlsIssue.warning(null, "a warning"))))
                .isInstanceOf(io.github.b077as.stheno.SthenoException.class);
    }

    @Test
    void errorThrownByAHandlerReleasesTheMessage() {
        InboundProcessor processor = processor(InboundRouter.builder().fallback(doc -> {
            throw new StackOverflowError();
        }).build());
        BuiltEnvelope env = TestDocs.invoiceEnvelope("88888888-8888-8888-8888-888888888888");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> processor.process(env.getXml(), null))
                .isInstanceOf(StackOverflowError.class);
        assertThat(store.size()).isZero();
    }

    @Test
    void documentWithoutHandlerIsRejectedAsFailureOfDelivery() {
        InboundProcessor processor = new InboundProcessor(store, InboundRouter.builder().build(), List.of(recorder),
                UnhandledDocumentPolicy.REJECT);
        HandlingResult result = processor.process(TestDocs.invoiceEnvelope("33333333-3333-3333-3333-333333333333").getXml(), null);
        assertThat(result.getOutcome().getStatus()).isEqualTo(InboundOutcome.Status.REJECTED);
        assertThat(result.getOutcome().getIssues()).singleElement()
                .extracting(MlsIssue::getReason).isEqualTo(MlsIssue.Reason.FD);
    }

    @Test
    void handlerReturningNothingCountsAsFailure() {
        InboundProcessor processor = processor(InboundRouter.builder().fallback(doc -> null).build());
        HandlingResult result = processor.process(TestDocs.invoiceEnvelope("44444444-4444-4444-4444-444444444444").getXml(), null);
        assertThat(result.getStatus()).isEqualTo(HandlingResult.Status.FAILED);
    }

    @Test
    void routesByDocumentTypeWithOrWithoutScheme() {
        InboundRouter router = InboundRouter.builder()
                .documentType("busdox-docid-qns::" + TestDocs.INVOICE_DOCTYPE, doc -> InboundOutcome.accepted("IT"))
                .fallback(doc -> InboundOutcome.accepted("FR"))
                .build();
        HandlingResult result = processor(router).process(TestDocs.invoiceEnvelope("55555555-5555-5555-5555-555555555555").getXml(), null);
        assertThat(result.getOutcome().getReceiverCountry()).isEqualTo("IT");
    }

    @Test
    void receivedMlsGoesToTheMlsHandlerAndIsNeverAcknowledged() {
        List<ReceivedMls> seen = new ArrayList<>();
        InboundProcessor processor = processor(InboundRouter.builder()
                .fallback(doc -> {
                    throw new AssertionError("an MLS must not reach the business document handler");
                })
                .mls((mls, doc) -> seen.add(mls))
                .build());

        HandlingResult result = processor.process(TestDocs.mlsEnvelope("my-sent-invoice-envelope", true).getXml(), null);
        assertThat(result.getStatus()).isEqualTo(HandlingResult.Status.HANDLED);
        assertThat(result.getDocument().isMessageLevelStatus()).isTrue();
        assertThat(result.isAcknowledgeable()).isFalse();
        assertThat(seen).singleElement().satisfies(mls -> {
            assertThat(mls.getResponseCode()).isEqualTo("RE");
            assertThat(mls.isRejected()).isTrue();
            assertThat(mls.getReferencedInstanceIdentifier()).isEqualTo("my-sent-invoice-envelope");
            assertThat(mls.getIssues()).singleElement().satisfies(issue -> {
                assertThat(issue.getReason()).isEqualTo(MlsIssue.Reason.BV);
                assertThat(issue.getLocation()).isEqualTo("/Invoice/cbc:ID");
                assertThat(issue.getDescription()).isEqualTo("Invoice number already used");
            });
        });
        assertThat(result.getMls()).isEqualTo(seen.get(0));
    }

    @Test
    void duplicateKeyIgnoresReceiverCase() {
        InboundDocument a = InboundDocument.builder().instanceIdentifier("x").receiverId("iso6523-actorid-upis::0088:ABC").build();
        InboundDocument b = InboundDocument.builder().instanceIdentifier("x").receiverId("0088:abc").build();
        assertThat(MessageKey.of(a)).isEqualTo(MessageKey.of(b));
    }

    @Test
    void brokenListenerDoesNotBreakTheFlow() {
        InboundListener broken = new InboundListener() {
            @Override
            public void onReceived(InboundDocument document) {
                throw new IllegalStateException("listener bug");
            }
        };
        InboundProcessor processor = new InboundProcessor(store, InboundRouter.builder().fallback(d -> InboundOutcome.accepted(null)).build(),
                List.of(broken));
        assertThat(processor.process(TestDocs.invoiceEnvelope("66666666-6666-6666-6666-666666666666").getXml(), null).getStatus())
                .isEqualTo(HandlingResult.Status.HANDLED);
    }

    private InboundProcessor processor(InboundRouter router) {
        return new InboundProcessor(store, router, List.of(recorder));
    }
}
