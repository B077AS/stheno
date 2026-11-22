package io.github.b077as.stheno.id;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Bean Validation constraint: the annotated string is a valid Peppol participant identifier ({@link PeppolIds#checkParticipantId}),
 * with or without scheme. {@code null} is valid (combine with {@code @NotNull}).
 */
@Documented
@Constraint(validatedBy = PeppolIdValidators.Participant.class)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.TYPE_USE, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface PeppolParticipantId {

    String message() default "is not a valid Peppol participant identifier";

    /** {@link IdUsage#NEW} (default) refuses deprecated and unknown schemes; {@link IdUsage#RECEIVED} only warns about them. */
    IdUsage usage() default IdUsage.NEW;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
