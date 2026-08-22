package com.mrnobody.agent.resilience;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.mrnobody.agent.core.Tier;

import org.junit.Test;

public class RetryPolicyTest {

    @Test
    public void transientReadRetriesOnce() {
        OperationFailure failure = FailureClassifier.fromHttp(503, "HTTP 503", 100L);
        assertTrue(RetryPolicy.shouldRetry(failure, 0, Tier.READ, false));
        assertFalse(RetryPolicy.shouldRetry(failure, 1, Tier.READ, false));
    }

    @Test
    public void effectRequiresIdempotency() {
        OperationFailure failure = FailureClassifier.fromHttp(429, "rate limited", 100L);
        assertFalse(RetryPolicy.shouldRetry(failure, 0, Tier.EXEC, false));
        assertTrue(RetryPolicy.shouldRetry(failure, 0, Tier.EXEC, true));
    }

    @Test
    public void authAndValidationNeverRetry() {
        assertFalse(RetryPolicy.shouldRetry(
                FailureClassifier.fromHttp(401, "bad key", 0), 0, Tier.READ, true));
        assertFalse(RetryPolicy.shouldRetry(
                FailureClassifier.fromHttp(400, "bad request", 0), 0, Tier.READ, true));
    }

    @Test
    public void classifierRecognizesProviderMessages() {
        assertTrue(FailureClassifier.fromMessage("HTTP 429: slow down").retryable);
        assertFalse(FailureClassifier.fromMessage("The API key was rejected").retryable);
        assertTrue(FailureClassifier.fromMessage("request timed out").ambiguous);
    }
}
