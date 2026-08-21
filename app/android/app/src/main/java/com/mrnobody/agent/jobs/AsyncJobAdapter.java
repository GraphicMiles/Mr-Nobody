package com.mrnobody.agent.jobs;

import android.content.Context;

import com.mrnobody.agent.core.Cancellation;

import java.util.Map;

/**
 * Platform-neutral submit/poll/retrieve contract.
 *
 * <p>Adapters receive the harness idempotency key on submission. Polling is a
 * read and may be retried later; submission itself is never repeated with a
 * different key.
 */
public interface AsyncJobAdapter {

    String id();

    Snapshot submit(Context context, Map<String, String> request,
                    String idempotencyKey, Cancellation cancellation) throws Exception;

    Snapshot poll(Context context, String externalJobId,
                  Cancellation cancellation) throws Exception;

    /** Reconcile an ambiguous submit by the original client key. */
    default Snapshot reconcile(Context context, String idempotencyKey,
                               Cancellation cancellation) throws Exception {
        return null;
    }

    default Snapshot cancel(Context context, String externalJobId,
                            Cancellation cancellation) throws Exception {
        return null;
    }

    final class Snapshot {
        public final AsyncJob.Status status;
        public final String externalJobId;
        public final String resultRef;
        public final String error;
        public final long retryAfterMs;
        public final long actualCostMicros;

        public Snapshot(AsyncJob.Status status, String externalJobId, String resultRef,
                        String error, long retryAfterMs, long actualCostMicros) {
            this.status = status == null ? AsyncJob.Status.UNKNOWN : status;
            this.externalJobId = externalJobId == null ? "" : externalJobId;
            this.resultRef = resultRef == null ? "" : resultRef;
            this.error = error == null ? "" : error;
            this.retryAfterMs = Math.max(0L, retryAfterMs);
            this.actualCostMicros = Math.max(0L, actualCostMicros);
        }
    }
}
