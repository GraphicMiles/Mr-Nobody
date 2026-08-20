package com.mrnobody.agent.tasks;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

/** Source wiring that cannot be executed against android.jar's throwing stubs. */
public final class TaskWorkerForegroundWiringTest {

    @Test
    public void workerPromotesBeforeDispatch() throws Exception {
        String source = read("src/main/java/com/mrnobody/agent/tasks/TaskWorker.java");
        int foreground = source.indexOf("setForegroundAsync");
        int dispatch = source.indexOf("dispatcher().dispatch");
        assertTrue(foreground >= 0);
        assertTrue(dispatch > foreground);
    }

    @Test
    public void foregroundNotificationDoesNotExposeTheInstruction() throws Exception {
        String source = read("src/main/java/com/mrnobody/agent/tasks/TaskForeground.java");
        assertTrue(!source.contains("task.activeInstruction()"));
        assertTrue(source.contains("notification_task_working_body"));
    }

    @Test
    public void workManagerForegroundServiceDeclaresDataSync() throws Exception {
        String manifest = read("src/main/AndroidManifest.xml");
        assertTrue(manifest.contains("androidx.work.impl.foreground.SystemForegroundService"));
        assertTrue(manifest.contains("android:foregroundServiceType=\"dataSync\""));
    }

    private static String read(String relative) throws Exception {
        return Files.readString(Path.of(relative), StandardCharsets.UTF_8);
    }
}
