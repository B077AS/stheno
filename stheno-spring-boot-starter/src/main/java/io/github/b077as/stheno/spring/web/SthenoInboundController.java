package io.github.b077as.stheno.spring.web;

import io.github.b077as.stheno.spring.InboundResponse;
import io.github.b077as.stheno.spring.SthenoInboundService;
import io.github.b077as.stheno.spring.SthenoProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The endpoint the Access Point forwards received documents to ({@code stheno.inbound.path}, default {@code /peppol/inbound}). It is a
 * normal Spring MVC endpoint: the application's Spring Security rules apply (exclude it from CSRF protection, the Access Point is a
 * machine client).
 */
@RestController
public class SthenoInboundController {

    private final SthenoInboundService service;
    private final SthenoProperties properties;

    public SthenoInboundController(SthenoInboundService service, SthenoProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @PostMapping(path = "${stheno.inbound.path:/peppol/inbound}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> receive(HttpServletRequest request) throws IOException {
        SthenoProperties.Inbound cfg = properties.getInbound();
        if (cfg.getAuthHeaderName() != null && !cfg.getAuthHeaderName().isBlank()) {
            String sent = request.getHeader(cfg.getAuthHeaderName());
            String expected = cfg.getAuthHeaderValue() == null ? "" : cfg.getAuthHeaderValue();
            if (sent == null || !MessageDigest.isEqual(sent.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8))) {
                return json(401, "{\"status\":\"unauthorized\"}");
            }
        }
        long max = cfg.getMaxEnvelopeSize().toBytes();
        if (request.getContentLengthLong() > max) {
            return json(413, "{\"status\":\"too large\"}");
        }
        byte[] body = readLimited(request.getInputStream(), max);
        if (body == null) {
            return json(413, "{\"status\":\"too large\"}");
        }
        Map<String, String> headers = new LinkedHashMap<>();
        for (String name : Collections.list(request.getHeaderNames())) {
            headers.put(name, request.getHeader(name));
        }
        InboundResponse response = service.accept(body, headers);
        return json(response.getStatus(), response.getBody());
    }

    private static ResponseEntity<String> json(int status, String body) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
    }

    private static byte[] readLimited(InputStream in, long max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384];
        long total = 0;
        int n;
        while ((n = in.read(buffer)) != -1) {
            total += n;
            if (total > max) {
                return null;
            }
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }
}
