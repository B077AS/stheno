package io.github.b077as.stheno.id;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Bean Validation constraint: the annotated string is a valid Peppol process identifier ({@link PeppolIds#checkProcessId}).
 * {@code null} is valid.
 */
@Documented
@Constraint(validatedBy = PeppolIdValidators.Process.class)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.TYPE_USE, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface PeppolProcessId {

    String message() default "is not a valid Peppol process identifier";

    IdUsage usage() default IdUsage.NEW;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
