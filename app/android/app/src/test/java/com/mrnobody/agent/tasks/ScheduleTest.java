package com.mrnobody.agent.tasks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Recurring tasks.
 *
 * <p>{@link TaskScheduler} only offered one-shot schedule/cancel, so "check
 * this every morning" had nowhere to live. Deliberately coarse rather than
 * cron: Android coalesces and defers background work regardless of what we
 * ask, so minute-level precision would be a promise the platform does not
 * keep.
 */
public class ScheduleTest {

    private static final long HOUR = 60 * 60 * 1000L;
    private static final long DAY = 24 * HOUR;
    private static final long NOW = 1_700_000_000_000L;

    @Test
    public void aOneShotRunsOnceAndThenNeverAgain() {
        Schedule s = Schedule.once();

        assertFalse(s.isRecurring());
        assertTrue("it has not run yet", s.nextRunAt(0, NOW) > 0);
        assertEquals("having run, it is done", 0L, s.nextRunAt(NOW, NOW + HOUR));
    }

    @Test
    public void arecurringScheduleKeepsComingBack() {
        Schedule s = new Schedule(Schedule.Repeat.DAILY, NOW);
        long next = s.nextRunAt(NOW, NOW + HOUR);

        assertEquals(NOW + DAY, next);
        assertFalse(s.isDue(NOW, NOW + HOUR));
        assertTrue(s.isDue(NOW, NOW + DAY));
    }

    @Test
    public void aphoneThatWasOffDoesNotRunSevenTimesOnWaking() {
        Schedule s = new Schedule(Schedule.Repeat.DAILY, NOW);

        // Last run a week ago; six runs were missed.
        long wakeUp = NOW + 7 * DAY;
        long next = s.nextRunAt(NOW, wakeUp);

        assertEquals("it should run once, now — not catch up", wakeUp, next);
    }

    @Test
    public void anIntervalBelowThePlatformFloorIsRaised() {
        // Asking for less than WorkManager honours would silently not happen,
        // so it is clamped here where it can be seen.
        Schedule s = new Schedule(Schedule.Repeat.HOURLY, NOW);
        assertTrue(s.effectiveIntervalMs() >= Schedule.MIN_INTERVAL_MS);
    }

    @Test
    public void aFutureFirstRunIsRespected() {
        long tomorrow = NOW + DAY;
        Schedule s = new Schedule(Schedule.Repeat.DAILY, tomorrow);

        assertEquals(tomorrow, s.nextRunAt(0, NOW));
        assertFalse(s.isDue(0, NOW));
    }

    @Test
    public void aPastFirstRunFiresImmediately() {
        Schedule s = new Schedule(Schedule.Repeat.DAILY, NOW - DAY);
        assertEquals(NOW, s.nextRunAt(0, NOW));
        assertTrue(s.isDue(0, NOW));
    }

    @Test
    public void oneShotIntervalIsZero() {
        assertEquals(0L, Schedule.once().effectiveIntervalMs());
    }

    @Test
    public void unknownNamesFallBackToOnce() {
        assertEquals(Schedule.Repeat.NEVER, Schedule.Repeat.fromName("hourly-ish"));
        assertEquals(Schedule.Repeat.NEVER, Schedule.Repeat.fromName(null));
        assertEquals(Schedule.Repeat.DAILY, Schedule.Repeat.fromName(" daily "));
    }

    @Test
    public void everyRepeatDescribesItself() {
        for (Schedule.Repeat r : Schedule.Repeat.values()) {
            assertFalse(r.name(), r.label().isEmpty());
        }
        assertEquals("once", Schedule.once().describe());
    }
}
