package dev.skullzz.donutflipper.api;

/**
 * Token bucket enforcing the API's documented ceiling of 250 requests per minute
 * per key.
 *
 * <p>Configured to spend only a fraction of the budget (see
 * {@code rateLimitUtilisation}). The headroom is not politeness -- the daemon is
 * not the only thing using your key. The Minecraft mod may poll alongside it,
 * and a manual probe run shares the same quota. Running at 100% means the first
 * moment anything else touches the API, the collector starts eating 429s and
 * silently loses sweeps, which corrupts the sale history the valuations depend on.
 *
 * <p>Refills continuously rather than in per-minute steps, so a burst at the
 * start of one window cannot double up against a burst at the start of the next.
 */
public final class RateLimiter {

    private final double capacity;
    private final double refillPerNanosecond;

    private double tokens;
    private long lastRefillNanos;

    /**
     * @param requestsPerMinute effective budget after the safety margin is applied
     */
    public RateLimiter(double requestsPerMinute) {
        if (requestsPerMinute <= 0) {
            throw new IllegalArgumentException("requestsPerMinute must be positive");
        }
        // Burst allowance of one second's worth, minimum 1. Enough to let a page
        // sweep get going without stuttering, small enough that we never present
        // the server with a wall of requests.
        this.capacity = Math.max(1.0, requestsPerMinute / 60.0);
        this.refillPerNanosecond = requestsPerMinute / 60.0 / 1_000_000_000.0;
        this.tokens = capacity;
        this.lastRefillNanos = System.nanoTime();
    }

    /** Blocks until a request may be issued. */
    public synchronized void acquire() throws InterruptedException {
        while (true) {
            refill();
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return;
            }
            double needed = 1.0 - tokens;
            long waitNanos = (long) Math.ceil(needed / refillPerNanosecond);
            long waitMillis = Math.max(1L, waitNanos / 1_000_000L);
            wait(waitMillis);
        }
    }

    private void refill() {
        long now = System.nanoTime();
        long elapsed = now - lastRefillNanos;
        if (elapsed <= 0) {
            return;
        }
        tokens = Math.min(capacity, tokens + elapsed * refillPerNanosecond);
        lastRefillNanos = now;
    }

    /**
     * Drains the bucket. Called after a 429 so we back off from a genuinely
     * empty budget rather than immediately spending whatever tokens our own
     * accounting thinks are still available -- the server's count is the one
     * that matters, and it just told us we were wrong.
     */
    public synchronized void penalise() {
        refill();
        tokens = 0.0;
    }

    public synchronized double availableTokens() {
        refill();
        return tokens;
    }
}
