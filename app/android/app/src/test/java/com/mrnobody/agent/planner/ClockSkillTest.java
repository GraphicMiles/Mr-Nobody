package com.mrnobody.agent.planner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

/**
 * Rule 3: time/date/day questions come from the device clock, zero network.
 * The skill must also refuse everything that merely sounds time-ish, because
 * a clock answer to "how old is messi" would be nonsense with a timestamp.
 */
public class ClockSkillTest {

    /** 2026-08-20 14:33 in Lagos (UTC+1). */
    private static final Clock LAGOS = Clock.fixed(
            Instant.parse("2026-08-20T13:33:00Z"), ZoneId.of("Africa/Lagos"));

    // ------------------------------------------------------------- matching

    @Test
    public void theDeviceObservedQueryAnswersLocally() {
        // "whats the time" ran a five-page 53-second research task on-device.
        String a = ClockSkill.answer("whats the time", LAGOS);
        assertNotNull(a);
        assertTrue(a, a.contains("14:33"));
        assertTrue(a, a.contains("2:33 pm"));
        assertTrue(a, a.contains("Thursday, 20 August 2026"));
        assertTrue(a, a.contains("Africa/Lagos"));
        assertTrue(a, a.contains("no network"));
    }

    @Test
    public void punctuationAndFillerDoNotMatter() {
        assertNotNull(ClockSkill.answer("What's the time?", LAGOS));
        assertNotNull(ClockSkill.answer("please tell me the time now", LAGOS));
        assertNotNull(ClockSkill.answer("What time is it right now?", LAGOS));
    }

    @Test
    public void dateAndDayQuestionsAnswerWithTheFullDate() {
        String a = ClockSkill.answer("what day is it today", LAGOS);
        assertNotNull(a);
        assertTrue(a, a.startsWith("Today is Thursday, 20 August 2026"));
        assertNotNull(ClockSkill.answer("whats todays date", LAGOS));
        assertNotNull(ClockSkill.answer("what is the date", LAGOS));
    }

    // ------------------------------------------------------------ refusals

    @Test
    public void questionsThatMerelyContainTimeWordsFallThrough() {
        assertNull(ClockSkill.answer("how old is messi", LAGOS));
        assertNull(ClockSkill.answer("screen time settings on android", LAGOS));
        assertNull(ClockSkill.answer("the time machine by h g wells summary", LAGOS));
        assertNull(ClockSkill.answer("best time to visit iceland", LAGOS));
        assertNull(ClockSkill.answer("download a png icon from pngtree", LAGOS));
        assertNull(ClockSkill.answer("", LAGOS));
        assertNull(ClockSkill.answer(null, LAGOS));
    }

    @Test
    public void timeArithmeticIsNotAClockQuestion() {
        assertNull(ClockSkill.answer("what time is it in 30 minutes", LAGOS));
    }

    // ------------------------------------------------------------ timezones

    @Test
    public void aNamedCityAnswersInThatZone() {
        String a = ClockSkill.answer("what time is it in london", LAGOS);
        assertNotNull(a);
        // August: London is UTC+1, same wall time as Lagos that day.
        assertTrue(a, a.contains("14:33"));
        assertTrue(a, a.contains("Europe/London"));

        String ny = ClockSkill.answer("what time is it in new york", LAGOS);
        assertNotNull(ny);
        assertTrue(ny, ny.contains("09:33"));
        assertTrue(ny, ny.contains("America/New_York"));
    }

    @Test
    public void anUnresolvablePlaceFallsThroughToResearch() {
        // A wrong timezone stated confidently is worse than a search.
        assertNull(ClockSkill.answer("what time is it in atlantis", LAGOS));
        assertNull(ClockSkill.answer("what time is it in the qing dynasty", LAGOS));
    }

    @Test
    public void utcIsSpokenForDirectly() {
        String a = ClockSkill.answer("what time is it in utc", LAGOS);
        assertNotNull(a);
        assertTrue(a, a.contains("13:33"));
    }

    @Test
    public void zoneResolutionIsDeterministic() {
        assertEquals(ZoneId.of("Europe/London"), ClockSkill.resolveZone("london"));
        assertEquals(ZoneId.of("Africa/Lagos"), ClockSkill.resolveZone("lagos"));
        assertNull(ClockSkill.resolveZone("nowhereville"));
    }
}
