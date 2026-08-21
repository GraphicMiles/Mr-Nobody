package com.mrnobody.agent.tasks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

public class SubmissionFingerprintTest {

    @Test
    public void harmlessWhitespaceDoesNotDefeatDoubleTapDedup() {
        String first = SubmissionFingerprint.of("create   a poster ", "local", "thread-1");
        String second = SubmissionFingerprint.of("create a poster", "LOCAL", "thread-1");
        assertEquals(first, second);
    }

    @Test
    public void caseSensitiveInstructionsAreNotCollapsed() {
        String first = SubmissionFingerprint.of("export Logo.PNG", "local", "thread-1");
        String second = SubmissionFingerprint.of("export logo.png", "local", "thread-1");
        assertNotEquals(first, second);
    }

    @Test
    public void contextAndWorkerRemainPartOfSubmissionIdentity() {
        String local = SubmissionFingerprint.of("create a poster", "local", "thread-1");
        String remote = SubmissionFingerprint.of("create a poster", "remote", "thread-1");
        String otherThread = SubmissionFingerprint.of("create a poster", "local", "thread-2");
        assertNotEquals(local, remote);
        assertNotEquals(local, otherThread);
    }
}
