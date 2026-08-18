package com.mrnobody.agent.tasks;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Telling a stuck task from a dead one.
 *
 * <p>{@link TaskReconciler} decides staleness from when the task row was last
 * written, which works because a task writes on every step — and fails exactly
 * where it matters: a task inside one long step writes nothing, so it looks
 * identical to a task whose process died.
 */
public class HeartbeatTest {

    private static final long NOW = 1_700_000_000_000L;

    @Test
    public void arecentBeatMeansAlive() {
        assertFalse(Heartbeat.isDead(NOW - 1_000L, NOW));
    }

    @Test
    public void prolongedSilenceMeansDead() {
        assertTrue(Heartbeat.isDead(NOW - (Heartbeat.DEAD_AFTER_MS + 1), NOW));
    }

    @Test
    public void aFewMissedBeatsAreToleratedBeforeGivingUp() {
        // A phone suspending an app briefly is ordinary; declaring a healthy
        // task dead loses real work, and the cost of waiting is only that a
        // Resume button appears slightly later.
        long twoMissed = Heartbeat.INTERVAL_MS * 2;
        assertFalse(Heartbeat.isDead(NOW - twoMissed, NOW));
    }

    @Test
    public void aTaskThatHasNeverBeatenIsNotPresumedDead() {
        // It may have only just been picked up. Killing it would be a race,
        // not a recovery.
        assertFalse(Heartbeat.isDead(0, NOW));
        assertFalse(Heartbeat.isDead(-1, NOW));
    }

    @Test
    public void abackwardsClockDoesNotKillEveryRunningTask() {
        // An NTP correction must not read as "every task died at once".
        assertFalse(Heartbeat.isDead(NOW + 60_000L, NOW));
    }

    @Test
    public void abeatIsDueWhenTheIntervalHasPassed() {
        assertTrue(Heartbeat.isDue(0, NOW));
        assertFalse(Heartbeat.isDue(NOW - 1_000L, NOW));
        assertTrue(Heartbeat.isDue(NOW - Heartbeat.INTERVAL_MS, NOW));
    }

    @Test
    public void abeatIsDueIfTheClockJumpedBackwards() {
        assertTrue(Heartbeat.isDue(NOW + 5_000L, NOW));
    }

    @Test
    public void theRecoveryMessageDoesNotBlameTheUser() {
        String reason = Heartbeat.recoveredReason();
        assertTrue(reason, reason.contains("run it again"));
    }
}
