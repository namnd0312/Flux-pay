package com.fluxpay.gateway.webhook;

import java.util.Map;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Receives provider webhooks (IPNs). Reads the body as raw bytes so the signature can be
 * verified over the exact bytes sent (a {@code @RequestBody} DTO would re-serialize and
 * break the signature). Enforces a size cap before processing. The response is always 200
 * once the signature is valid; the body reports what happened.
 */
@RestController
public class WebhookController {

    private static final int MAX_BODY_BYTES = 64 * 1024;

    private final WebhookIngestionService ingestion;

    public WebhookController(WebhookIngestionService ingestion) {
        this.ingestion = ingestion;
    }

    @PostMapping("/webhooks/{providerCode}")
    public WebhookResponse receive(@PathVariable String providerCode,
            @RequestBody(required = false) byte[] body,
            @RequestHeader Map<String, String> headers) {
        byte[] rawBody = body == null ? new byte[0] : body;
        if (rawBody.length > MAX_BODY_BYTES) {
            throw new WebhookPayloadTooLargeException(MAX_BODY_BYTES);
        }
        WebhookResult result = ingestion.process(providerCode, rawBody, headers);
        return new WebhookResponse(true, result.duplicate(), result.applied(), result.rejectReason());
    }

    /** Always-200 body. {@code rejectReason} is null unless a business rule rejected the event. */
    public record WebhookResponse(boolean received, boolean duplicate, boolean applied, String rejectReason) {
    }
}
