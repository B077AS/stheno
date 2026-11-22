package io.github.b077as.stheno.id;

import io.github.b077as.stheno.TestDocs;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PeppolIdAnnotationsTest {

    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();

    static class Customer {
        @PeppolParticipantId
        String peppolId;
        @PeppolParticipantId(usage = IdUsage.RECEIVED)
        String receivedFrom;
        @PeppolDocumentTypeId
        String documentType;
        @PeppolProcessId
        String process;
    }

    @AfterAll
    static void close() {
        FACTORY.close();
    }

    @Test
    void validValuesAndNullPass() {
        Customer c = new Customer();
        assertThat(VALIDATOR.validate(c)).isEmpty();
        c.peppolId = TestDocs.SENDER;
        c.receivedFrom = "1234:unknown-scheme";
        c.documentType = TestDocs.INVOICE_DOCTYPE;
        c.process = TestDocs.BILLING_PROCESS;
        assertThat(VALIDATOR.validate(c)).isEmpty();
    }

    @Test
    void violationsNameTheProblem() {
        Customer c = new Customer();
        c.peppolId = "0208:0123456748";
        c.documentType = "";
        Set<ConstraintViolation<Customer>> violations = VALIDATOR.validate(c);
        assertThat(violations).hasSize(2);
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("peppolId")
                && v.getMessage().contains("check digit"));
    }

    @Test
    void messageInterpolationCharactersInTheValueAreHarmless() {
        Customer c = new Customer();
        c.peppolId = "${1+1}{x}";
        assertThat(VALIDATOR.validate(c)).singleElement().satisfies(v -> assertThat(v.getMessage()).contains("${1+1}{x}"));
    }
}
