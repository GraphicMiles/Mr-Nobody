package com.mrnobody.agent.jobs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.content.Context;

import com.mrnobody.agent.core.Cancellation;
import com.mrnobody.agent.execution.ExecutionIdentity;
import com.mrnobody.agent.execution.InMemoryExecutionLedger;

import org.junit.Test;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class AsyncJobCoordinatorTest {

    private static final Context NO_CONTEXT = null;

    @Test
    public void duplicateSubmitReturnsTheSameDurableJob() {
        InMemoryAsyncJobStore jobs = new InMemoryAsyncJobStore();
        AsyncJobCoordinator coordinator = new AsyncJobCoordinator(
                jobs, new InMemoryExecutionLedger());
        FakeAdapter adapter = new FakeAdapter();
        ExecutionIdentity identity = identity("run-a");

        AsyncJob first = coordinator.submit(NO_CONTEXT, adapter,
                Collections.singletonMap("prompt", "poster"), identity,
                15_000L, Cancellation.NONE);
        AsyncJob second = coordinator.submit(NO_CONTEXT, adapter,
                Collections.singletonMap("prompt", "poster"), identity,
                15_000L, Cancellation.NONE);

        assertNotNull(first);
        assertEquals(first.localJobId, second.localJobId);
        assertEquals(1, adapter.submits.get());
        assertEquals("remote-1", first.externalJobId);
    }

    @Test
    public void preparedJobResumesSubmissionAfterProcessDeath() {
        InMemoryAsyncJobStore jobs = new InMemoryAsyncJobStore();
        InMemoryExecutionLedger ledger = new InMemoryExecutionLedger();
        AsyncJobCoordinator coordinator = new AsyncJobCoordinator(jobs, ledger);
        FakeAdapter adapter = new FakeAdapter();
        ExecutionIdentity identity = identity("run-prepared");
        long now = System.currentTimeMillis();
        jobs.create(new AsyncJob("job-prepared", identity.taskId(), identity.runId(),
                adapter.id(), identity.idempotencyKey(), identity.operationFingerprint(),
                AsyncJob.Status.PREPARED, "", "", "", 0L,
                0L, 0L, now, now));

        AsyncJob resumed = coordinator.submit(NO_CONTEXT, adapter,
                Collections.emptyMap(), identity, 0L, Cancellation.NONE);

        assertEquals(AsyncJob.Status.QUEUED, resumed.status);
        assertEquals(1, adapter.submits.get());
    }

    @Test
    public void nonTerminalSubmissionSchedulesDurableBackgroundPolling() {
        InMemoryAsyncJobStore jobs = new InMemoryAsyncJobStore();
        RecordingScheduler scheduler = new RecordingScheduler();
        AsyncJobCoordinator coordinator = new AsyncJobCoordinator(
                jobs, new InMemoryExecutionLedger(), scheduler);
        AsyncJob submitted = coordinator.submit(NO_CONTEXT, new FakeAdapter(),
                Collections.emptyMap(), identity("run-scheduled"), 0L, Cancellation.NONE);
        assertEquals(submitted.localJobId, scheduler.scheduled);
    }

    @Test
    public void cancellationWithoutItsAdapterRemainsUnknown() {
        InMemoryAsyncJobStore jobs = new InMemoryAsyncJobStore();
        AsyncJobCoordinator coordinator = new AsyncJobCoordinator(
                jobs, new InMemoryExecutionLedger(), new RecordingScheduler());
        FakeAdapter adapter = new FakeAdapter();
        ExecutionIdentity identity = identity("run-cancel");
        AsyncJob submitted = coordinator.submit(NO_CONTEXT, adapter,
                Collections.emptyMap(), identity, 0L, Cancellation.NONE);
        AsyncJob cancelled = coordinator.cancel(NO_CONTEXT, null, submitted, Cancellation.NONE);
        assertEquals(AsyncJob.Status.UNKNOWN, cancelled.status);
    }

    @Test
    public void pollUpdatesTheSameJobInsteadOfSubmittingAgain() {
        InMemoryAsyncJobStore jobs = new InMemoryAsyncJobStore();
        AsyncJobCoordinator coordinator = new AsyncJobCoordinator(
                jobs, new InMemoryExecutionLedger());
        FakeAdapter adapter = new FakeAdapter();
        ExecutionIdentity identity = identity("run-b");
        AsyncJob submitted = coordinator.submit(NO_CONTEXT, adapter,
                Collections.emptyMap(), identity, 0L, Cancellation.NONE);

        AsyncJob done = coordinator.refresh(NO_CONTEXT, adapter, submitted,
                identity, Cancellation.NONE);

        assertEquals(AsyncJob.Status.SUCCEEDED, done.status);
        assertEquals("artifact-1", done.resultRef);
        assertEquals(1, adapter.submits.get());
        assertEquals(1, adapter.polls.get());
    }

    private static ExecutionIdentity identity(String run) {
        return ExecutionIdentity.of(9L, run, "design.create", 0,
                "design", "create", Collections.singletonMap("kind", "poster"));
    }

    private static final class RecordingScheduler implements AsyncJobScheduler {
        String scheduled = "";
        @Override public void schedule(Context context, AsyncJob job) {
            scheduled = job.localJobId;
        }
        @Override public void cancel(Context context, String id) { }
    }

    private static final class FakeAdapter implements AsyncJobAdapter {
        final AtomicInteger submits = new AtomicInteger();
        final AtomicInteger polls = new AtomicInteger();

        @Override public String id() { return "fake-design"; }

        @Override
        public Snapshot submit(Context context, Map<String, String> request,
                               String idempotencyKey, Cancellation cancellation) {
            submits.incrementAndGet();
            return new Snapshot(AsyncJob.Status.QUEUED,
                    "remote-1", "", "", 100L, 0L);
        }

        @Override
        public Snapshot poll(Context context, String externalJobId,
                             Cancellation cancellation) {
            polls.incrementAndGet();
            return new Snapshot(AsyncJob.Status.SUCCEEDED,
                    externalJobId, "artifact-1", "", 0L, 12_000L);
        }
    }
}
