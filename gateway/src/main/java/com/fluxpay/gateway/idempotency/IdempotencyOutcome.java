package com.fluxpay.gateway.idempotency;

/**
 * The result of {@link IdempotencyService#begin}. The filter switches on {@link #kind()}:
 * <ul>
 *   <li>{@code NEW} — this caller claimed the key; run the request and store the response;</li>
 *   <li>{@code REPLAY} — a completed response exists; replay {@link #responseStatus()} and
 *       {@link #responseBody()} without touching the controller (so no provider call);</li>
 *   <li>{@code IN_PROGRESS} — another request holds a fresh lease; answer 409;</li>
 *   <li>{@code MISMATCH} — the key was reused with a different body; answer 422.</li>
 * </ul>
 */
public record IdempotencyOutcome(Kind kind, int responseStatus, String responseBody) {

    public enum Kind { NEW, REPLAY, IN_PROGRESS, MISMATCH }

    private static final IdempotencyOutcome NEW = new IdempotencyOutcome(Kind.NEW, 0, null);
    private static final IdempotencyOutcome IN_PROGRESS = new IdempotencyOutcome(Kind.IN_PROGRESS, 0, null);
    private static final IdempotencyOutcome MISMATCH = new IdempotencyOutcome(Kind.MISMATCH, 0, null);

    public static IdempotencyOutcome ofNew() {
        return NEW;
    }

    public static IdempotencyOutcome inProgress() {
        return IN_PROGRESS;
    }

    public static IdempotencyOutcome mismatch() {
        return MISMATCH;
    }

    public static IdempotencyOutcome replay(int status, String body) {
        return new IdempotencyOutcome(Kind.REPLAY, status, body);
    }
}
