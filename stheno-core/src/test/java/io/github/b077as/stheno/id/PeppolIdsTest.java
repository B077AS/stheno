package io.github.b077as.stheno.id;

import com.helger.peppolid.codelist.ECodeListItemState;
import com.helger.peppolid.peppol.pidscheme.IPeppolParticipantIdentifierScheme;
import com.helger.peppolid.peppol.pidscheme.PeppolParticipantIdentifierSchemeManager;
import io.github.b077as.stheno.TestDocs;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PeppolIdsTest {

    @Test
    void validParticipantWithAndWithoutScheme() {
        IdCheck plain = PeppolIds.checkParticipantId("0208:0123456749", IdUsage.NEW);
        IdCheck withScheme = PeppolIds.checkParticipantId("iso6523-actorid-upis::0208:0123456749", IdUsage.NEW);
        assertThat(plain.isValid()).isTrue();
        assertThat(plain.getWarnings()).isEmpty();
        assertThat(plain.getNormalized()).isEqualTo("iso6523-actorid-upis::0208:0123456749");
        assertThat(withScheme.getNormalized()).isEqualTo(plain.getNormalized());
    }

    @Test
    void wrongCheckDigitIsAnError() {
        // Belgian enterprise number: peppol-commons checks the mod 97 check digit
        IdCheck check = PeppolIds.checkParticipantId("0208:0123456748", IdUsage.RECEIVED);
        assertThat(check.isValid()).isFalse();
        assertThat(check.getErrors()).singleElement().asString().contains("check digit");
    }

    @Test
    void syntaxErrors() {
        assertThat(PeppolIds.checkParticipantId("not an id", IdUsage.NEW).isValid()).isFalse();
        assertThat(PeppolIds.checkParticipantId("0088:with space", IdUsage.NEW).isValid()).isFalse();
        assertThat(PeppolIds.checkParticipantId(null, IdUsage.NEW).isValid()).isFalse();
        assertThat(PeppolIds.checkParticipantId("", IdUsage.NEW).getNormalized()).isNull();
    }

    @Test
    void unknownSchemeIsErrorOnlyForNewDocuments() {
        assertThat(PeppolIds.checkParticipantId("1234:abc", IdUsage.NEW).isValid()).isFalse();
        IdCheck received = PeppolIds.checkParticipantId("1234:abc", IdUsage.RECEIVED);
        assertThat(received.isValid()).isTrue();
        assertThat(received.getWarnings()).singleElement().asString().contains("1234");
    }

    @Test
    void deprecatedSchemeIsErrorOnlyForNewDocuments() {
        IPeppolParticipantIdentifierScheme deprecated = PeppolParticipantIdentifierSchemeManager.getAllSchemes().stream()
                .filter(s -> s.getState() == ECodeListItemState.DEPRECATED)
                .findFirst()
                .orElseThrow();
        String id = deprecated.getISO6523Code() + ":12345";
        assertThat(PeppolIds.checkParticipantId(id, IdUsage.NEW).getErrors()).anyMatch(e -> e.contains("deprecated"));
        IdCheck received = PeppolIds.checkParticipantId(id, IdUsage.RECEIVED);
        assertThat(received.getWarnings()).anyMatch(w -> w.contains("deprecated"));
        assertThat(received.getErrors()).noneMatch(e -> e.contains("deprecated"));
    }

    @Test
    void participantValuesAreCaseInsensitive() {
        assertThat(PeppolIds.isSameParticipant("0088:ABC", "iso6523-actorid-upis::0088:abc")).isTrue();
        assertThat(PeppolIds.isSameParticipant("0088:abc", "0010:abc")).isFalse();
        assertThat(PeppolIds.normalizeParticipantId("0088:ABc")).isEqualTo("iso6523-actorid-upis::0088:abc");
    }

    @Test
    void documentTypeWithAndWithoutScheme() {
        IdCheck plain = PeppolIds.checkDocumentTypeId(TestDocs.INVOICE_DOCTYPE, IdUsage.NEW);
        IdCheck withScheme = PeppolIds.checkDocumentTypeId("busdox-docid-qns::" + TestDocs.INVOICE_DOCTYPE, IdUsage.NEW);
        assertThat(plain.isValid()).isTrue();
        assertThat(plain.getWarnings()).isEmpty();
        assertThat(plain.getNormalized()).isEqualTo("busdox-docid-qns::" + TestDocs.INVOICE_DOCTYPE);
        assertThat(withScheme.getNormalized()).isEqualTo(plain.getNormalized());
    }

    @Test
    void documentTypeIsCaseSensitive() {
        IdCheck upper = PeppolIds.checkDocumentTypeId(TestDocs.INVOICE_DOCTYPE.toUpperCase(), IdUsage.NEW);
        // Not in the code list in that spelling: only a warning, never stricter than the official checks
        assertThat(upper.getWarnings()).isNotEmpty();
    }

    @Test
    void processId() {
        assertThat(PeppolIds.checkProcessId(TestDocs.BILLING_PROCESS, IdUsage.NEW).getWarnings()).isEmpty();
        assertThat(PeppolIds.checkProcessId("cenbii-procid-ubl::" + TestDocs.BILLING_PROCESS, IdUsage.NEW).isValid()).isTrue();
        assertThat(PeppolIds.checkProcessId("urn:something:unknown", IdUsage.NEW).getWarnings()).isNotEmpty();
    }

    @Test
    void envelopeCountryCodes() {
        assertThat(CountryCodes.isValidEnvelopeCountry("BE")).isTrue();
        assertThat(CountryCodes.isValidEnvelopeCountry("1A")).isTrue();
        assertThat(CountryCodes.isValidEnvelopeCountry("be")).isFalse();
        assertThat(CountryCodes.isValidEnvelopeCountry("BEL")).isFalse();
        assertThat(CountryCodes.isValidEnvelopeCountry(null)).isFalse();
    }
}
