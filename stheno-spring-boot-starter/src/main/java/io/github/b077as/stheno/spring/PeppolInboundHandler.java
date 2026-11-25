package io.github.b077as.stheno.spring;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a bean method that handles received business documents. Signature:
 * <pre>
 * InboundOutcome handle(InboundDocument document)
 * </pre>
 * Return {@code InboundOutcome.accepted(receiverCountry)} once the document is stored, {@code InboundOutcome.rejected(...)} when it is
 * technically unacceptable (not for business objections, see {@code InboundOutcome.Status#REJECTED}). Throw an exception when it
 * cannot be processed right now (it is retried).
 * <p>
 * {@link #documentType()} lists the document type identifiers (with or without the {@code busdox-docid-qns} scheme, compared case
 * sensitively); empty = every document no other handler takes. Methods with document types are tried first.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface PeppolInboundHandler {

    String[] documentType() default {};
}
