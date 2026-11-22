package io.github.b077as.stheno.spring;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a bean method that is told about Message Level Status answers to documents the application sent. Signature:
 * <pre>
 * void onMls(ReceivedMls mls)
 * void onMls(ReceivedMls mls, InboundDocument document)
 * </pre>
 * {@code mls.getReferencedInstanceIdentifier()} is the SBDH instance identifier of the sent document. At most one such method.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface PeppolMlsHandler {
}
