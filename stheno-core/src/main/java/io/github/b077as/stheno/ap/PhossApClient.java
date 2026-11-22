package io.github.b077as.stheno.ap;

import io.github.b077as.stheno.SthenoException;
import io.github.b077as.stheno.inbound.InboundOutcome;
import io.github.b077as.stheno.inbound.MlsIssue;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Client for the receiver backend API of phoss-ap (0.13+): reporting the receiver country (C4) for Peppol Reporting and giving the
 * Message Level Status verdict of the application. Every call is about a document the Access Point already received; nothing is
 * transmitted to the Peppol network by this client (phoss-ap itself then sends the MLS to the sender's Access Point).
 * <p>
 * Thread-safe.
 */
@Slf4j
public final class PhossApClient {

    private static final String TOKEN_HEADER = "X-Token";

    private final URI baseUrl;
    private final String token;
    private final Duration timeout;
    private final HttpClient http;

    /**
     * @param baseUrl base URL of phoss-ap, e.g. {@code https://ap.example.com} (the {@code /api/...} paths are appended)
     * @param token   value of phoss-ap's {@code phase4.api.requiredtoken}, sent as {@code X-Token}; {@code null} for none
     * @param timeout connect and request timeout
     */
    public PhossApClient(URI baseUrl, String token, Duration timeout) {
        this(baseUrl, token, timeout, HttpClient.newBuilder().connectTimeout(timeout).build());
    }

    public PhossApClient(URI baseUrl, String token, Duration timeout, HttpClient http) {
        String url = Objects.requireNonNull(baseUrl, "baseUrl").toString();
        this.baseUrl = URI.create(url.endsWith("/") ? url.substring(0, url.length() - 1) : url);
        this.token = token;
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.http = Objects.requireNonNull(http, "http");
    }

    /**
     * {@code POST /api/inbound/report}: the receiver country of a received document, needed by the Access Point for Peppol Reporting.
     *
     * @return {@link ApCallResult#DONE}, or {@link ApCallResult#ALREADY_DONE} if the Access Point already had a country for it
     * @throws SthenoException {@code ACCESS_POINT_CALL_FAILED} for any other answer
     */
    public ApCallResult reportReceiverCountry(String sbdhInstanceId, String receiverCountry) {
        String query = "sbdhInstanceID=" + enc(sbdhInstanceId) + "&c4CountryCode=" + enc(receiverCountry);
        HttpRequest request = request("/api/inbound/report?" + query).POST(HttpRequest.BodyPublishers.noBody()).build();
        HttpResponse<String> response = send(request);
        return switch (response.statusCode()) {
            case 200 -> ApCallResult.DONE;
            case 400 -> ApCallResult.ALREADY_DONE;
            default -> throw failure("reporting the receiver country of " + sbdhInstanceId, response);
        };
    }

    /**
     * {@code POST /api/mls/send}: the application's verdict, from which phoss-ap builds and sends the MLS.
     *
     * @return {@link ApCallResult#DONE}; {@link ApCallResult#ALREADY_DONE} if an MLS was already decided (HTTP 409);
     *         {@link ApCallResult#NOT_APPLICABLE} if MLS sending is disabled at the Access Point (503) or the document is itself an MLS (422)
     * @throws SthenoException {@code ACCESS_POINT_CALL_FAILED} for any other answer
     */
    public ApCallResult sendMlsVerdict(String sbdhInstanceId, InboundOutcome outcome) {
        HttpRequest request = request("/api/mls/send")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mlsRequestJson(sbdhInstanceId, outcome), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = send(request);
        return switch (response.statusCode()) {
            case 200 -> ApCallResult.DONE;
            case 409 -> ApCallResult.ALREADY_DONE;
            case 422, 503 -> {
                log.warn("phoss-ap did not accept the MLS verdict for {} (HTTP {}): {}", sbdhInstanceId, response.statusCode(),
                        response.body());
                yield ApCallResult.NOT_APPLICABLE;
            }
            default -> throw failure("sending the MLS verdict of " + sbdhInstanceId, response);
        };
    }

    /** The JSON body of {@code /api/mls/send} (phoss-ap {@code MlsSendRequest} / {@code MlsSendIssue}). */
    static String mlsRequestJson(String sbdhInstanceId, InboundOutcome outcome) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sbdhInstanceID", sbdhInstanceId);
        body.put("responseCode", outcome.getMlsResponseCode());
        body.put("responseText", outcome.getText());
        if (!outcome.getIssues().isEmpty()) {
            List<Object> issues = new ArrayList<>();
            for (MlsIssue issue : outcome.getIssues()) {
                Map<String, Object> i = new LinkedHashMap<>();
                i.put("statusReasonCode", issue.getReason().name());
                i.put("errorField", issue.getLocation());
                i.put("description", issue.getDescription());
                issues.add(i);
            }
            body.put("issues", issues);
        }
        return Json.write(body);
    }

    private HttpRequest.Builder request(String pathAndQuery) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + pathAndQuery)).timeout(timeout);
        if (token != null && !token.isEmpty()) {
            b.header(TOKEN_HEADER, token);
        }
        return b;
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new SthenoException(SthenoException.ErrorCode.ACCESS_POINT_CALL_FAILED,
                    "Calling the Access Point at " + request.uri() + " failed: " + ex, ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new SthenoException(SthenoException.ErrorCode.ACCESS_POINT_CALL_FAILED,
                    "Interrupted while calling the Access Point at " + request.uri(), ex);
        }
    }

    private static SthenoException failure(String what, HttpResponse<String> response) {
        String body = response.body() == null ? "" : response.body();
        return new SthenoException(SthenoException.ErrorCode.ACCESS_POINT_CALL_FAILED,
                "The Access Point refused " + what + ": HTTP " + response.statusCode() + (body.isBlank() ? "" : " " + body));
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
