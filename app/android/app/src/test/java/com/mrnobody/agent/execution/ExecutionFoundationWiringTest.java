package com.mrnobody.agent.execution;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/** Pins the Android wiring that pure ledger tests cannot execute off-device. */
public class ExecutionFoundationWiringTest {

    private static String source(String relative) throws Exception {
        return new String(Files.readAllBytes(Paths.get("src/main/java/" + relative)),
                StandardCharsets.UTF_8);
    }

    @Test
    public void applicationConnectsLedgerToPipelineAndWorker() throws Exception {
        String app = source("com/mrnobody/browser/MrNobodyApp.java");
        assertTrue(app.contains("executionLedger = new SqliteExecutionLedger(this)"));
        assertTrue(app.contains("de.pipeline().setLedger(executionLedger)"));
        assertTrue(app.contains("new LocalWorker(agentEngine, executionLedger)"));
    }

    @Test
    public void taskSchemaMigratesRunAndSubmissionIdentity() throws Exception {
        String store = source("com/mrnobody/agent/tasks/TaskStore.java");
        assertTrue(store.contains("private static final int VERSION = 8"));
        assertTrue(store.contains("C_RUN_ID"));
        assertTrue(store.contains("C_SUBMISSION_KEY"));
        assertTrue(store.contains("findLiveFingerprint"));
    }

    @Test
    public void downloadsPassTheHarnessKeyToTheirDurableStore() throws Exception {
        String tool = source("com/mrnobody/agent/tools/DownloadTool.java");
        String store = source("com/mrnobody/browser/download/DownloadStore.java");
        assertTrue(tool.contains("execution.idempotencyKey()"));
        assertTrue(store.contains("idx_downloads_request_key"));
        assertTrue(store.contains("findByRequestKey"));
    }

    @Test
    public void clearTaskStateAlsoClearsRecoveryState() throws Exception {
        String activity = source("com/mrnobody/browser/MainActivity.java");
        assertTrue(activity.contains("MrNobodyApp.executionLedger().clearAll()"));
        assertTrue(activity.contains("MrNobodyApp.asyncJobs().clearAll()"));
    }
}
