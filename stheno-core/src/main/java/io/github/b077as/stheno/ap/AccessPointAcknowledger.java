package io.github.b077as.stheno.ap;

import io.github.b077as.stheno.SthenoException;
import io.github.b077as.stheno.inbound.HandlingResult;
import io.github.b077as.stheno.inbound.InboundOutcome;
import lombok.extern.slf4j.Slf4j;

import java.util.Objects;

/**
 * Tells the application's own Access Point what happened to a received document, each part switchable on its own:
 * <ul>
 * <li><b>receiver country</b> ({@code /api/inbound/report}): needed by the Access Point for Peppol Reporting;</li>
 * <li><b>MLS verdict</b> ({@code /api/mls/send}): the Access Point builds and sends the Message Level Status from it. Peppol only
 * allows the positive {@code AP} code after a confirmation from the receiver's system, which is exactly this call.</li>
 * </ul>
 * Failures are logged and reported in the {@link Acknowledgement}, never thrown: the document itself was already taken over.
 */
@Slf4j
public final class AccessPointAcknowledger {

    private final PhossApClient client;
    private final boolean reportReceiverCountry;
    private final boolean sendMlsVerdict;

    public AccessPointAcknowledger(PhossApClient client, boolean reportReceiverCountry, boolean sendMlsVerdict) {
        this.client = Objects.requireNonNull(client, "client");
        this.reportReceiverCountry = reportReceiverCountry;
        this.sendMlsVerdict = sendMlsVerdict;
    }

    public boolean isSendMlsVerdict() {
        return sendMlsVerdict;
    }

    public boolean isReportReceiverCountry() {
        return reportReceiverCountry;
    }

    /**
     * @param result            the handling result
     * @param countryAlreadySent {@code true} if the receiver country already reached the Access Point (e.g. in the synchronous
     *                           forwarding answer), so it is not reported again
     */
    public Acknowledgement acknowledge(HandlingResult result, boolean countryAlreadySent) {
        return acknowledge(result, countryAlreadySent, null);
    }

    /**
     * Like {@link #acknowledge(HandlingResult, boolean)}, for a retry: only the calls that failed in the previous attempt are made
     * again, the others keep their result.
     *
     * @param previous result of the previous attempt, or {@code null} for the first one
     */
    public Acknowledgement acknowledge(HandlingResult result, boolean countryAlreadySent, Acknowledgement previous) {
        if (!result.isAcknowledgeable()) {
            return Acknowledgement.NOTHING;
        }
        String id = result.getDocument().getInstanceIdentifier();
        InboundOutcome outcome = result.getOutcome();
        boolean rejected = outcome.getStatus() == InboundOutcome.Status.REJECTED;

        ApCallResult country = previous == null ? ApCallResult.SKIPPED : previous.getReceiverCountry();
        if ((previous == null || country == ApCallResult.FAILED)
                && reportReceiverCountry && !countryAlreadySent && outcome.getReceiverCountry() != null && !rejected) {
            country = call("receiver country", id, () -> client.reportReceiverCountry(id, outcome.getReceiverCountry()));
        }

        ApCallResult mls = previous == null ? ApCallResult.SKIPPED : previous.getMlsVerdict();
        if ((previous == null || mls == ApCallResult.FAILED) && sendMlsVerdict) {
            mls = call("MLS verdict " + outcome.getMlsResponseCode(), id, () -> client.sendMlsVerdict(id, outcome));
        }
        boolean rejectionNotDelivered = rejected && mls != ApCallResult.DONE && mls != ApCallResult.FAILED;
        return new Acknowledgement(country, mls, rejectionNotDelivered);
    }

    private static ApCallResult call(String what, String id, java.util.function.Supplier<ApCallResult> call) {
        try {
            ApCallResult r = call.get();
            log.info("Access Point acknowledgement ({}) for {}: {}", what, id, r);
            return r;
        } catch (SthenoException ex) {
            log.error("Access Point acknowledgement ({}) for {} failed: {}", what, id, ex.getMessage());
            return ApCallResult.FAILED;
        }
    }
}
