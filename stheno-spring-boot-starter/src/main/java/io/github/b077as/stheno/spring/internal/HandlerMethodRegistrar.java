package io.github.b077as.stheno.spring.internal;

import io.github.b077as.stheno.inbound.InboundDocument;
import io.github.b077as.stheno.inbound.InboundHandler;
import io.github.b077as.stheno.inbound.InboundOutcome;
import io.github.b077as.stheno.inbound.InboundRouter;
import io.github.b077as.stheno.inbound.MlsHandler;
import io.github.b077as.stheno.inbound.ReceivedMls;
import io.github.b077as.stheno.reply.InvoiceResponse;
import io.github.b077as.stheno.reply.InvoiceResponseBuilder;
import io.github.b077as.stheno.reply.InvoiceResponseHandler;
import io.github.b077as.stheno.reply.InvoiceResponseReader;
import io.github.b077as.stheno.spring.PeppolInboundHandler;
import io.github.b077as.stheno.spring.PeppolInvoiceResponseHandler;
import io.github.b077as.stheno.spring.PeppolMlsHandler;
import lombok.Value;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Collects {@link PeppolInboundHandler}, {@link PeppolInvoiceResponseHandler} and {@link PeppolMlsHandler} methods of all beans and
 * builds the {@link InboundRouter} from them. Wrong signatures fail the application start with a message naming the method.
 */
public class HandlerMethodRegistrar implements BeanPostProcessor {

    private final List<Registered> typed = new ArrayList<>();
    private final List<Registered> fallbacks = new ArrayList<>();
    private final List<Registered> responseHandlers = new ArrayList<>();
    private final List<Registered> mlsHandlers = new ArrayList<>();

    @Value
    private static class Registered {
        Object bean;
        Method method;
        String[] documentTypes;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        Class<?> type = AopUtils.getTargetClass(bean);
        Map<Method, PeppolInboundHandler> inbound = MethodIntrospector.selectMethods(type,
                (MethodIntrospector.MetadataLookup<PeppolInboundHandler>) m -> AnnotatedElementUtils.findMergedAnnotation(m, PeppolInboundHandler.class));
        inbound.forEach((method, annotation) -> {
            checkInboundSignature(beanName, method);
            Registered r = new Registered(bean, method, annotation.documentType());
            synchronized (this) {
                (annotation.documentType().length == 0 ? fallbacks : typed).add(r);
            }
        });
        Map<Method, PeppolInvoiceResponseHandler> responses = MethodIntrospector.selectMethods(type,
                (MethodIntrospector.MetadataLookup<PeppolInvoiceResponseHandler>) m -> AnnotatedElementUtils.findMergedAnnotation(m, PeppolInvoiceResponseHandler.class));
        responses.forEach((method, annotation) -> {
            checkResponseSignature(beanName, method);
            synchronized (this) {
                responseHandlers.add(new Registered(bean, method, new String[0]));
            }
        });
        Map<Method, PeppolMlsHandler> mls =MethodIntrospector.selectMethods(type,
                (MethodIntrospector.MetadataLookup<PeppolMlsHandler>) m -> AnnotatedElementUtils.findMergedAnnotation(m, PeppolMlsHandler.class));
        mls.forEach((method, annotation) -> {
            checkMlsSignature(beanName, method);
            synchronized (this) {
                mlsHandlers.add(new Registered(bean, method, new String[0]));
            }
        });
        return bean;
    }

    /** Number of registered handler methods (business documents, Invoice Responses and MLS). */
    public synchronized int getHandlerCount() {
        return typed.size() + fallbacks.size() + responseHandlers.size() + mlsHandlers.size();
    }

    /** Builds the router from the collected methods. */
    public synchronized InboundRouter buildRouter() {
        if (fallbacks.size() > 1) {
            throw new IllegalStateException("More than one @PeppolInboundHandler without documentType: " + describe(fallbacks));
        }
        if (mlsHandlers.size() > 1) {
            throw new IllegalStateException("More than one @PeppolMlsHandler: " + describe(mlsHandlers));
        }
        if (responseHandlers.size() > 1) {
            throw new IllegalStateException("More than one @PeppolInvoiceResponseHandler: " + describe(responseHandlers));
        }
        InboundRouter.Builder builder = InboundRouter.builder();
        for (Registered r : typed) {
            for (String documentType : r.getDocumentTypes()) {
                if (!responseHandlers.isEmpty() && isInvoiceResponseType(documentType)) {
                    throw new IllegalStateException("Invoice Responses would have two handlers: " + describe(responseHandlers) + " and "
                            + describe(List.of(r)));
                }
                builder.documentType(documentType, inboundHandler(r));
            }
        }
        if (!responseHandlers.isEmpty()) {
            builder.when(InvoiceResponseReader::isInvoiceResponse,
                    InvoiceResponseHandler.asInboundHandler(responseHandler(responseHandlers.get(0))));
        }
        if (!fallbacks.isEmpty()) {
            builder.fallback(inboundHandler(fallbacks.get(0)));
        }
        if (!mlsHandlers.isEmpty()) {
            builder.mls(mlsHandler(mlsHandlers.get(0)));
        }
        return builder.build();
    }

    private static InboundHandler inboundHandler(Registered r) {
        r.getMethod().setAccessible(true);
        return document -> (InboundOutcome) invoke(r, document);
    }

    private static InvoiceResponseHandler responseHandler(Registered r) {
        r.getMethod().setAccessible(true);
        boolean withDocument = r.getMethod().getParameterCount() == 2;
        return (response, document) -> (InboundOutcome) (withDocument ? invoke(r, response, document) : invoke(r, response));
    }

    private static boolean isInvoiceResponseType(String documentType) {
        // With or without the identifier scheme, as the router matches it
        return documentType.equals(InvoiceResponseBuilder.DOCUMENT_TYPE_ID)
                || documentType.endsWith("::" + InvoiceResponseBuilder.DOCUMENT_TYPE_ID);
    }

    private static MlsHandler mlsHandler(Registered r) {
        r.getMethod().setAccessible(true);
        boolean withDocument = r.getMethod().getParameterCount() == 2;
        return (mls, document) -> {
            if (withDocument) {
                invoke(r, mls, document);
            } else {
                invoke(r, mls);
            }
        };
    }

    private static Object invoke(Registered r, Object... args) throws Exception {
        try {
            return r.getMethod().invoke(r.getBean(), args);
        } catch (InvocationTargetException ex) {
            if (ex.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw ex;
        }
    }

    private static void checkInboundSignature(String beanName, Method m) {
        if (m.getParameterCount() != 1 || m.getParameterTypes()[0] != InboundDocument.class
                || !InboundOutcome.class.isAssignableFrom(m.getReturnType())) {
            throw new IllegalStateException("@PeppolInboundHandler method " + beanName + "." + m.getName()
                    + " must have the signature 'InboundOutcome name(InboundDocument document)'");
        }
    }

    private static void checkResponseSignature(String beanName, Method m) {
        Class<?>[] p = m.getParameterTypes();
        boolean parameters = (p.length == 1 && p[0] == InvoiceResponse.class)
                || (p.length == 2 && p[0] == InvoiceResponse.class && p[1] == InboundDocument.class);
        if (!parameters || !InboundOutcome.class.isAssignableFrom(m.getReturnType())) {
            throw new IllegalStateException("@PeppolInvoiceResponseHandler method " + beanName + "." + m.getName()
                    + " must have the signature 'InboundOutcome name(InvoiceResponse response)' or "
                    + "'InboundOutcome name(InvoiceResponse response, InboundDocument document)'");
        }
    }

    private static void checkMlsSignature(String beanName, Method m) {
        Class<?>[] p = m.getParameterTypes();
        boolean ok = (p.length == 1 && p[0] == ReceivedMls.class) || (p.length == 2 && p[0] == ReceivedMls.class && p[1] == InboundDocument.class);
        if (!ok) {
            throw new IllegalStateException("@PeppolMlsHandler method " + beanName + "." + m.getName()
                    + " must have the signature 'void name(ReceivedMls mls)' or 'void name(ReceivedMls mls, InboundDocument document)'");
        }
    }

    private static String describe(List<Registered> list) {
        return list.stream().map(r -> r.getBean().getClass().getSimpleName() + "." + r.getMethod().getName()).toList().toString();
    }
}
