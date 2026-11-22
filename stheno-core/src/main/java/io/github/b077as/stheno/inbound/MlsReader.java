package io.github.b077as.stheno.inbound;

import com.helger.peppol.mls.PeppolMLSBuilder;
import com.helger.peppol.mls.PeppolMLSLineResponseBuilder;
import com.helger.peppol.mls.PeppolMLSLineResponseResponseBuilder;
import com.helger.peppol.mls.PeppolMLSMarshaller;
import io.github.b077as.stheno.SthenoException;
import oasis.names.specification.ubl.schema.xsd.applicationresponse_21.ApplicationResponseType;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads a received Peppol Message Level Status with peppol-commons' {@link PeppolMLSMarshaller} and {@link PeppolMLSBuilder}.
 */
public final class MlsReader {

    /**
     * @throws SthenoException {@link SthenoException.ErrorCode#INVALID_ENVELOPE} if the document is no readable MLS
     */
    public ReceivedMls read(InboundDocument document) {
        if (document.getPayload() == null || document.getPayload().getKind() != InboundPayload.Kind.XML) {
            throw new SthenoException(SthenoException.ErrorCode.INVALID_ENVELOPE, "An MLS must have an XML payload");
        }
        PeppolMLSBuilder mls;
        try {
            ApplicationResponseType ar = new PeppolMLSMarshaller().read(document.getPayload().getXml());
            if (ar == null) {
                throw new SthenoException(SthenoException.ErrorCode.INVALID_ENVELOPE, "The payload is not a UBL ApplicationResponse");
            }
            mls = PeppolMLSBuilder.createForApplicationResponse(ar);
        } catch (IllegalArgumentException ex) {
            throw new SthenoException(SthenoException.ErrorCode.INVALID_ENVELOPE, "Not a valid Peppol MLS: " + ex.getMessage(), ex);
        }

        List<MlsIssue> issues = new ArrayList<>();
        for (PeppolMLSLineResponseBuilder line : mls.lineResponsesAsBuilders()) {
            for (PeppolMLSLineResponseResponseBuilder response : line.responsesAsBuilders()) {
                MlsIssue.Reason reason = response.statusReasonCode() == null
                        ? MlsIssue.Reason.BV
                        : MlsIssue.Reason.valueOf(response.statusReasonCode().getID());
                issues.add(new MlsIssue(reason, line.errorField(), response.description() == null ? "" : response.description()));
            }
        }

        return new ReceivedMls(mls.responseCode().getID(),
                mls.referenceId(),
                mls.responseText(),
                List.copyOf(issues),
                mls.id(),
                mls.senderParticipantID() == null ? null : mls.senderParticipantID().getURIEncoded());
    }
}
