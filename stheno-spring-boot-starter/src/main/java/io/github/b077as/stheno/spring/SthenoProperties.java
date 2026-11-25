package io.github.b077as.stheno.spring;

import io.github.b077as.stheno.inbound.UnhandledDocumentPolicy;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The {@code stheno.*} settings.
 *
 * <pre>
 * stheno.inbound.path=/peppol/inbound                 # where the Access Point forwards documents to
 * stheno.inbound.forwarding-mode=async                # async | sync : phoss-ap forwarding.mode http_post_async / http_post_sync
 * stheno.inbound.processing=background                # background | inline : when the handlers run
 * stheno.inbound.auth-header-name=Authorization       # header the Access Point sends (phoss-ap forwarding.http.headers.1.*)
 * stheno.inbound.auth-header-value=Bearer secret
 * stheno.inbound.store=memory                         # memory | jdbc (needs a DataSource bean)
 * stheno.inbound.duplicate-retention=30d
 * stheno.inbound.processing-timeout=10m               # undecided longer than this = lost: reported, can be delivered again
 * stheno.inbound.unhandled-documents=retry            # retry | reject : business documents no handler takes
 *
 * stheno.access-point.base-url=https://ap.example.com # the application's own phoss-ap; nothing is acknowledged without it
 * stheno.access-point.token=...                       # phoss-ap phase4.api.requiredtoken
 * stheno.access-point.report-receiver-country=true    # POST /api/inbound/report (Peppol Reporting)
 * stheno.access-point.mls-verdict=true                # POST /api/mls/send (the MLS is built from the handler's outcome)
 *
 * stheno.lookup.enabled=false                         # needs peppol-smp-client
 * stheno.lookup.network=production                    # production | test
 *
 * stheno.validation.enabled=false                     # needs phive-xml, phive-rules-peppol, ddd
 * stheno.validation.warm-up=eu.peppol.bis3:invoice:latest
 * </pre>
 */
@Getter
@Setter
@ConfigurationProperties("stheno")
public class SthenoProperties {

    private Inbound inbound = new Inbound();
    private AccessPoint accessPoint = new AccessPoint();
    private Lookup lookup = new Lookup();
    private Validation validation = new Validation();

    /** How the Access Point forwards documents (must match phoss-ap's {@code forwarding.mode}). */
    public enum ForwardingMode {
        /** {@code http_post_async}: the Access Point only looks at the HTTP status. */
        ASYNC,
        /** {@code http_post_sync}: the Access Point reads {@code countryCodeC4} / {@code retry} from the JSON answer. */
        SYNC
    }

    /** When the application's handlers run. */
    public enum Processing {
        /** After the Access Point got its answer (recommended: the Access Point waits inside the Peppol exchange). */
        BACKGROUND,
        /** Before answering: a handler exception makes the Access Point retry, but slow handlers delay the Peppol exchange. */
        INLINE
    }

    /** Where received messages are remembered for duplicate detection. */
    public enum Store {
        MEMORY,
        JDBC
    }

    @Getter
    @Setter
    public static class Inbound {
        /** Switches the inbound endpoint off. */
        private boolean enabled = true;
        private String path = "/peppol/inbound";
        private ForwardingMode forwardingMode = ForwardingMode.ASYNC;
        private Processing processing = Processing.BACKGROUND;
        /** Name of a header the Access Point must send; empty = no check (then protect the path otherwise, e.g. Spring Security). */
        private String authHeaderName;
        private String authHeaderValue;
        private DataSize maxEnvelopeSize = DataSize.ofMegabytes(100);
        private Store store = Store.MEMORY;
        /** Table of the JDBC store. */
        private String jdbcTable = "stheno_received_message";
        /** Create the JDBC table at startup if it does not exist. */
        private boolean jdbcCreateTable = true;
        /** How long received messages are remembered; longer than the Access Point retries or replays. */
        private Duration duplicateRetention = Duration.ofDays(30);
        /**
         * After this time a document without a decision counts as lost: it is reported, and the JDBC store lets a new delivery take it
         * over. Must be longer than the slowest handler.
         */
        private Duration processingTimeout = Duration.ofMinutes(10);
        /**
         * Business documents no handler takes: {@code retry} answers {@code 503} so that the Access Point delivers them again,
         * {@code reject} rejects them (MLS {@code RE}, reason {@code FD}).
         */
        private UnhandledDocumentPolicy unhandledDocuments = UnhandledDocumentPolicy.RETRY;
        /** Background processing: how often a failing handler is retried before giving up. */
        private int handlerRetryAttempts = 3;
        private Duration handlerRetryDelay = Duration.ofMinutes(1);
    }

    @Getter
    @Setter
    public static class AccessPoint {
        /** Base URL of the application's own phoss-ap. Without it nothing is acknowledged. */
        private URI baseUrl;
        private String token;
        private Duration timeout = Duration.ofSeconds(10);
        /** Report the receiver country (C4) for Peppol Reporting. */
        private boolean reportReceiverCountry = true;
        /**
         * Give the MLS verdict ({@code AP}/{@code AB}/{@code RE} with issues) through {@code /api/mls/send}. MLS is becoming mandatory;
         * switch it off only when the Access Point decides the MLS itself (phoss-ap {@code mls.sending.trigger=auto}).
         */
        private boolean mlsVerdict = true;
        /** Wait before acknowledging, so that the Access Point has processed the forwarding answer first. */
        private Duration acknowledgeDelay = Duration.ofMillis(500);
        private int retryAttempts = 3;
        private Duration retryDelay = Duration.ofSeconds(30);
    }

    /**
     * Peppol network for lookups. Own enum on purpose: the properties class must load without the optional lookup library
     * ({@code peppol-smp-client}), so it may not refer to {@code PeppolNetwork}.
     */
    public enum Network {
        PRODUCTION,
        TEST
    }

    @Getter
    @Setter
    public static class Lookup {
        private boolean enabled = false;
        private Network network = Network.PRODUCTION;
        /** Query this SMP directly instead of using the SML (DNS); signatures are still checked with the network's trust store. */
        private URI smpUrl;
        private Duration positiveTtl = Duration.ofHours(24);
        private Duration negativeTtl = Duration.ofMinutes(15);
        private int maxEntries = 10_000;
    }

    @Getter
    @Setter
    public static class Validation {
        private boolean enabled = false;
        /** Rule sets (phive VESIDs) to compile at startup. */
        private List<String> warmUp = new ArrayList<>();
    }
}
