package com.ultikits.ultitools.websocket;

/**
 * Exponential backoff reconnection strategy.
 * Delays increase exponentially: 5s, 10s, 20s, 40s... up to max 5 minutes.
 *
 * @author wisdomme
 * @version 1.0.0
 * @since 6.2.0
 */
public class ExponentialBackoffStrategy implements ReconnectStrategy {
    
    /**
     * Initial delay in milliseconds (5 seconds).
     */
    public static final long DEFAULT_INITIAL_DELAY = 5000L;
    
    /**
     * Maximum delay in milliseconds (5 minutes).
     */
    public static final long DEFAULT_MAX_DELAY = 300000L;
    
    /**
     * Default multiplier for exponential growth.
     */
    public static final double DEFAULT_MULTIPLIER = 2.0;
    
    /**
     * Default maximum number of attempts (0 = unlimited).
     */
    public static final int DEFAULT_MAX_ATTEMPTS = 0;
    
    private final long initialDelay;
    private final long maxDelay;
    private final double multiplier;
    private final int maxAttempts;
    
    private int attemptCount = 0;
    
    /**
     * Creates a strategy with default settings.
     */
    public ExponentialBackoffStrategy() {
        this(DEFAULT_INITIAL_DELAY, DEFAULT_MAX_DELAY, DEFAULT_MULTIPLIER, DEFAULT_MAX_ATTEMPTS);
    }
    
    /**
     * Creates a strategy with custom settings.
     *
     * @param initialDelay initial delay in milliseconds
     * @param maxDelay     maximum delay in milliseconds
     * @param multiplier   multiplier for exponential growth
     * @param maxAttempts  maximum attempts (0 for unlimited)
     */
    public ExponentialBackoffStrategy(long initialDelay, long maxDelay, double multiplier, int maxAttempts) {
        if (initialDelay <= 0) {
            throw new IllegalArgumentException("Initial delay must be positive");
        }
        if (maxDelay < initialDelay) {
            throw new IllegalArgumentException("Max delay must be >= initial delay");
        }
        if (multiplier < 1.0) {
            throw new IllegalArgumentException("Multiplier must be >= 1.0");
        }
        
        this.initialDelay = initialDelay;
        this.maxDelay = maxDelay;
        this.multiplier = multiplier;
        this.maxAttempts = maxAttempts;
    }
    
    @Override
    public long getNextDelay() {
        long delay = (long) (initialDelay * Math.pow(multiplier, attemptCount));
        attemptCount++;
        return Math.min(delay, maxDelay);
    }
    
    @Override
    public void reset() {
        attemptCount = 0;
    }
    
    @Override
    public int getAttemptCount() {
        return attemptCount;
    }
    
    @Override
    public boolean shouldContinue() {
        return maxAttempts == 0 || attemptCount < maxAttempts;
    }
    
    /**
     * Creates a strategy that tries forever with default backoff.
     *
     * @return an unlimited retry strategy
     */
    public static ExponentialBackoffStrategy unlimited() {
        return new ExponentialBackoffStrategy();
    }
    
    /**
     * Creates a strategy with limited attempts.
     *
     * @param maxAttempts the maximum number of attempts
     * @return a limited retry strategy
     */
    public static ExponentialBackoffStrategy withMaxAttempts(int maxAttempts) {
        return new ExponentialBackoffStrategy(
                DEFAULT_INITIAL_DELAY, DEFAULT_MAX_DELAY, DEFAULT_MULTIPLIER, maxAttempts);
    }
    
    /**
     * Creates a builder for custom configuration.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }
    
    /**
     * Builder for ExponentialBackoffStrategy.
     */
    public static class Builder {
        private long initialDelay = DEFAULT_INITIAL_DELAY;
        private long maxDelay = DEFAULT_MAX_DELAY;
        private double multiplier = DEFAULT_MULTIPLIER;
        private int maxAttempts = DEFAULT_MAX_ATTEMPTS;
        
        public Builder initialDelay(long initialDelay) {
            this.initialDelay = initialDelay;
            return this;
        }
        
        public Builder maxDelay(long maxDelay) {
            this.maxDelay = maxDelay;
            return this;
        }
        
        public Builder multiplier(double multiplier) {
            this.multiplier = multiplier;
            return this;
        }
        
        public Builder maxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
            return this;
        }
        
        public ExponentialBackoffStrategy build() {
            return new ExponentialBackoffStrategy(initialDelay, maxDelay, multiplier, maxAttempts);
        }
    }
}
