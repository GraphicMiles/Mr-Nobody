package com.mrnobody.agent.jobs;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

public class AsyncJobWorkerWiringTest {
    @Test public void workerReconnectsThroughPersistedAdapterAndLedgerIdentity() throws Exception {
        String source = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/mrnobody/agent/jobs/AsyncJobPollWorker.java")),
                StandardCharsets.UTF_8);
        assertTrue(source.contains("MrNobodyApp.asyncJobs().find(id)"));
        assertTrue(source.contains("MrNobodyApp.asyncJobAdapters().get(job.adapterId)"));
        assertTrue(source.contains("MrNobodyApp.executionLedger()"));
        assertTrue(source.contains("Task.Status.WAITING_EXTERNAL"));
    }
}
