package io.github.b077as.stheno.lookup;


import com.helger.peppol.sml.ISMLInfo;
import com.helger.peppol.smp.ESMPTransportProfile;
import com.helger.peppolid.IDocumentTypeIdentifier;
import com.helger.peppolid.IParticipantIdentifier;
import com.helger.peppolid.IProcessIdentifier;
import com.helger.smpclient.exception.SMPClientException;
import com.helger.smpclient.peppol.SMPClientReadOnly;
import com.helger.smpclient.url.SMPDNSResolutionException;
import com.helger.smpclient.url.dns.PeppolNaptrURLProvider;
import com.helger.xsds.peppol.smp1.EndpointType;
import com.helger.xsds.peppol.smp1.ServiceMetadataType;
import com.helger.xsds.peppol.smp1.SignedServiceMetadataType;
import io.github.b077as.stheno.SthenoException;
import io.github.b077as.stheno.id.PeppolIds;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.security.KeyStore;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * {@link PeppolLookup} over the Peppol SML (DNS U-NAPTR) and the participant's SMP, using peppol-commons' {@link SMPClientReadOnly}
 * (which also checks the SMP signature). Only the Peppol AS4 v2 transport profile counts. Needs {@code peppol-smp-client} on the
 * classpath; every call goes to the network, wrap it in {@link CachingPeppolLookup}.
 */
@Slf4j
public final class SmpPeppolLookup implements PeppolLookup {

    /** Source of the service metadata of one participant and document type; replaceable for tests. */
    @FunctionalInterface
    interface MetadataSource {
        /**
         * @return the service metadata, or {@code null} if the participant does not support the document type
         * @throws NotRegisteredException if the participant is not registered at all
         */
        ServiceMetadataType get(IParticipantIdentifier participant, IDocumentTypeIdentifier documentType) throws Exception;
    }

    static final class NotRegisteredException extends Exception {
        private static final long serialVersionUID = 1L;

        NotRegisteredException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final MetadataSource source;
    private final Clock clock;

    /** Lookup in the given Peppol network, through its SML (DNS), verifying SMP signatures with the official trust store. */
    public static SmpPeppolLookup via(PeppolNetwork network) {
        Objects.requireNonNull(network, "network");
        return viaSml(network.getSml(), network.getSmpTrustStore());
    }

    /** Lookup through any SML; SMP signatures are verified with the given trust store. */
    public static SmpPeppolLookup viaSml(ISMLInfo sml, KeyStore smpTrustStore) {
        Objects.requireNonNull(sml, "sml");
        Objects.requireNonNull(smpTrustStore, "smpTrustStore");
        return new SmpPeppolLookup((participant, documentType) -> {
            SMPClientReadOnly client;
            try {
                client = new SMPClientReadOnly(PeppolNaptrURLProvider.INSTANCE, participant, sml);
            } catch (SMPDNSResolutionException ex) {
                throw new NotRegisteredException("No SML/DNS entry for " + participant.getURIEncoded() + ": " + ex.getMessage(), ex);
            }
            client.setTrustStore(smpTrustStore);
            return metadataOf(client.getServiceMetadataOrNull(participant, documentType, null));
        }, Clock.systemUTC());
    }

    /** Lookup at one fixed SMP, without DNS (e.g. a test SMP); SMP signatures are verified with the given trust store. */
    public static SmpPeppolLookup viaSmp(URI smpUrl, KeyStore smpTrustStore) {
        SMPClientReadOnly client = new SMPClientReadOnly(Objects.requireNonNull(smpUrl, "smpUrl"));
        client.setTrustStore(Objects.requireNonNull(smpTrustStore, "smpTrustStore"));
        return new SmpPeppolLookup((participant, documentType) -> {
            if (client.getServiceGroupOrNull(participant) == null) {
                throw new NotRegisteredException(participant.getURIEncoded() + " is not registered at " + smpUrl, null);
            }
            return metadataOf(client.getServiceMetadataOrNull(participant, documentType, null));
        }, Clock.systemUTC());
    }

    SmpPeppolLookup(MetadataSource source, Clock clock) {
        this.source = source;
        this.clock = clock;
    }

    @Override
    public ReceiverCapability lookup(String participantId, String documentTypeId, String processId) {
        IParticipantIdentifier participant = PeppolIds.parseParticipantId(participantId);
        IDocumentTypeIdentifier documentType = PeppolIds.parseDocumentTypeId(documentTypeId);
        IProcessIdentifier process = PeppolIds.parseProcessId(processId);
        if (participant == null || documentType == null || process == null) {
            throw new SthenoException(SthenoException.ErrorCode.INVALID_INPUT, "Malformed identifier in lookup: participant '"
                    + participantId + "', document type '" + documentTypeId + "', process '" + processId + "'");
        }

        ReceiverCapability.Status status;
        String endpointUrl = null;
        try {
            ServiceMetadataType metadata = source.get(participant, documentType);
            if (metadata == null) {
                status = ReceiverCapability.Status.DOCUMENT_TYPE_NOT_SUPPORTED;
            } else {
                EndpointType endpoint = SMPClientReadOnly.getEndpointAt(metadata, process,
                        ESMPTransportProfile.TRANSPORT_PROFILE_PEPPOL_AS4_V2, LocalDateTime.now(clock.withZone(ZoneOffset.UTC)));
                if (endpoint == null) {
                    status = ReceiverCapability.Status.PROCESS_NOT_SUPPORTED;
                } else {
                    status = ReceiverCapability.Status.CAN_RECEIVE;
                    endpointUrl = SMPClientReadOnly.getEndpointAddress(endpoint);
                }
            }
        } catch (NotRegisteredException ex) {
            log.debug("{}", ex.getMessage());
            status = ReceiverCapability.Status.NOT_REGISTERED;
        } catch (SMPClientException ex) {
            throw new SthenoException(SthenoException.ErrorCode.LOOKUP_FAILED,
                    "SMP lookup of " + participant.getURIEncoded() + " failed: " + ex.getMessage(), ex);
        } catch (Exception ex) {
            throw new SthenoException(SthenoException.ErrorCode.LOOKUP_FAILED,
                    "Lookup of " + participant.getURIEncoded() + " failed: " + ex.getMessage(), ex);
        }
        return new ReceiverCapability(participant.getURIEncoded(), documentType.getURIEncoded(), process.getURIEncoded(), status,
                endpointUrl, clock.instant());
    }

    private static ServiceMetadataType metadataOf(SignedServiceMetadataType signed) {
        return signed == null ? null : signed.getServiceMetadata();
    }
}
