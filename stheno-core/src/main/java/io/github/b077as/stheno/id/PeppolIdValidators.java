package io.github.b077as.stheno.id;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.function.BiFunction;

/**
 * Bean Validation implementations of {@link PeppolParticipantId}, {@link PeppolDocumentTypeId} and {@link PeppolProcessId}. The
 * violation message lists the concrete problems found by {@link PeppolIds}.
 */
public final class PeppolIdValidators {

    private PeppolIdValidators() {
    }

    private abstract static class Base {
        private IdUsage usage;

        void init(IdUsage usage) {
            this.usage = usage;
        }

        boolean check(String value, ConstraintValidatorContext context, BiFunction<String, IdUsage, IdCheck> checker) {
            if (value == null) {
                return true;
            }
            IdCheck check = checker.apply(value, usage);
            if (check.isValid()) {
                return true;
            }
            context.disableDefaultConstraintViolation();
            String template = context.getDefaultConstraintMessageTemplate() + ": " + String.join("; ", check.getErrors());
            // Escape Bean Validation message interpolation characters coming from the value
            template = template.replace("\\", "\\\\").replace("{", "\\{").replace("}", "\\}").replace("$", "\\$");
            context.buildConstraintViolationWithTemplate(template).addConstraintViolation();
            return false;
        }
    }

    public static final class Participant extends Base implements ConstraintValidator<PeppolParticipantId, String> {
        @Override
        public void initialize(PeppolParticipantId annotation) {
            init(annotation.usage());
        }

        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return check(value, context, PeppolIds::checkParticipantId);
        }
    }

    public static final class DocumentType extends Base implements ConstraintValidator<PeppolDocumentTypeId, String> {
        @Override
        public void initialize(PeppolDocumentTypeId annotation) {
            init(annotation.usage());
        }

        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return check(value, context, PeppolIds::checkDocumentTypeId);
        }
    }

    public static final class Process extends Base implements ConstraintValidator<PeppolProcessId, String> {
        @Override
        public void initialize(PeppolProcessId annotation) {
            init(annotation.usage());
        }

        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return check(value, context, PeppolIds::checkProcessId);
        }
    }
}
