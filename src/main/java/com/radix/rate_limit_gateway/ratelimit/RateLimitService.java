package com.radix.rate_limit_gateway.ratelimit;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fixed-window rate limiter (in-memory).
 * Global rule: 5 requests per 60 seconds per (tenantId, apiKey, method, path).
 */
public class RateLimitService {

    private static final int LIMIT = 5;
    private static final int WINDOW_SECONDS = 60;

    // Key -> state
    private final ConcurrentHashMap<Key, State> states = new ConcurrentHashMap<>();

    public Decision check(String tenantId, String apiKey, String method, String path, long nowEpochSeconds) {
        Key key = new Key(tenantId, apiKey, method, path);

        // Atomically compute/update the state for this key.
        State updated = states.compute(key, (k, existing) -> {
            if (existing == null) {
                // First time seeing this key: start a new window, count=1
                return new State(nowEpochSeconds, 1);
            }

            long windowStart = existing.windowStartEpochSeconds;
            long windowEnd = windowStart + WINDOW_SECONDS;

            // If window expired, reset windowStart and count
            if (nowEpochSeconds >= windowEnd) {
                return new State(nowEpochSeconds, 1);
            }

            // Same window: increment count
            return new State(windowStart, existing.count + 1);
        });

        // Decide allow/block based on the updated count and window.
        long windowEnd = updated.windowStartEpochSeconds + WINDOW_SECONDS;

        if (updated.count <= LIMIT) {
            return new Decision(true, 0);
        }

        int retryAfter = (int) Math.max(0, windowEnd - nowEpochSeconds);
        return new Decision(false, retryAfter);
    }

    /** Output of a rate-limit decision. */
    public static final class Decision {
        public final boolean allowed;
        public final int retryAfterSeconds;

        public Decision(boolean allowed, int retryAfterSeconds) {
            this.allowed = allowed;
            this.retryAfterSeconds = retryAfterSeconds;
        }
    }

    /** Internal state for a fixed window. */
    private static final class State {
        private final long windowStartEpochSeconds;
        private final int count;

        private State(long windowStartEpochSeconds, int count) {
            this.windowStartEpochSeconds = windowStartEpochSeconds;
            this.count = count;
        }
    }

    /** Composite key: (tenantId, apiKey, method, path). */
    private static final class Key {
        private final String tenantId;
        private final String apiKey;
        private final String method;
        private final String path;

        private Key(String tenantId, String apiKey, String method, String path) {
            this.tenantId = tenantId;
            this.apiKey = apiKey;
            this.method = method;
            this.path = path;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key)) return false;
            Key key = (Key) o;
            return Objects.equals(tenantId, key.tenantId)
                    && Objects.equals(apiKey, key.apiKey)
                    && Objects.equals(method, key.method)
                    && Objects.equals(path, key.path);
        }

        @Override
        public int hashCode() {
            return Objects.hash(tenantId, apiKey, method, path);
        }
    }
}
