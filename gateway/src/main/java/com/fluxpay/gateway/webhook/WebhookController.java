package com.fluxpay.gateway.webhook;

import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.fluxpay.gateway.common.ProblemDetailWriter;

/**
 * Receives provider webhooks (IPNs). Reads the body as raw bytes so the signature can be
 * verified over the exact bytes sent (a {@code @RequestBody} DTO would re-serialize and
 * break the signature). Enforces a size cap before processing. Once the signature is valid
 * the answer is 200 with a body reporting what happened, or 503 when the provider should
 * send the same event again later (see {@link WebhookResult}).
 */
@RestController
public class WebhookController {

    private static final int MAX_BODY_BYTES = 64 * 1024;

    private final WebhookIngestionService ingestion;
    private final ProblemDetailWriter problems;

    public WebhookController(WebhookIngestionService ingestion, ProblemDetailWriter problems) {
        this.ingestion = ingestion;
        this.problems = problems;
    }

    @PostMapping("/webhooks/{providerCode}")
    public ResponseEntity<?> receive(@PathVariable String providerCode,
            @RequestBody(required = false) byte[] body,
            @RequestHeader Map<String, String> headers) {
        byte[] rawBody = body == null ? new byte[0] : body;
        if (rawBody.length > MAX_BODY_BYTES) {
            throw new WebhookPayloadTooLargeException(MAX_BODY_BYTES);
        }
        WebhookResult result = ingestion.process(providerCode, rawBody, headers);
        if (result.shouldRetry()) {
            ProblemDetail problem = problems.build(HttpStatus.SERVICE_UNAVAILABLE, "WEBHOOK_RETRY_LATER",
                    "The event cannot be applied yet. Send the same event again later.");
            problem.setProperty("reason", result.retryLater());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header(HttpHeaders.RETRY_AFTER, "1")
                    .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .body(problem);
        }
        return ResponseEntity.ok(
                new WebhookResponse(true, result.duplicate(), result.applied(), result.rejectReason()));
    }

    /** Body of a 200 answer. {@code rejectReason} is null unless a business rule rejected the event. */
    public record WebhookResponse(boolean received, boolean duplicate, boolean applied, String rejectReason) {
    }
}
