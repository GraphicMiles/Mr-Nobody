package com.mrnobody.agent.planner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Interval extraction is structure. Tracking verbs are not this class's
 * job anymore — those paraphrases live in {@link IntentClassifierParaphraseTest}.
 */
public class RecurrenceRequestTest {

    @Test
    public void anExplicitIntervalStillWins() {
        RecurrenceRequest.Request r = RecurrenceRequest.parse("check the price every day");
        assertTrue(r.isRecurring());
        assertTrue(r.explicit);
    }

    @Test
    public void adverbIntervalsAreStructure() {
        assertTrue(RecurrenceRequest.parse("check bitcoin hourly").isRecurring());
        assertTrue(RecurrenceRequest.parse("a weekly digest of the same").isRecurring());
    }

    @Test
    public void aOneOffQuestionIsNotAnInterval() {
        assertFalse(RecurrenceRequest.parse("what is the bitcoin price").isRecurring());
        assertFalse(RecurrenceRequest.parse("download infinity war from nkiri.ink").isRecurring());
    }

    @Test
    public void trackingWordingWithoutAnIntervalIsNotParsedHere() {
        // These used to be the phrase-list "fix". They must not schedule
        // anything on their own; the classifier owns that decision.
        assertFalse(RecurrenceRequest.parse(
                "keep up on any new Marvel announcements on X").isRecurring());
        assertFalse(RecurrenceRequest.parse("stay updated on the bitcoin price").isRecurring());
        assertFalse(RecurrenceRequest.parse("keep me posted about releases").isRecurring());
        assertFalse(RecurrenceRequest.parse("ping me about new studio news").isRecurring());
    }

    @Test
    public void forMonitorAssumesADefaultWhenNoIntervalWasNamed() {
        RecurrenceRequest.Request r = RecurrenceRequest.forMonitor(
                "if anything comes up, pass it along");
        assertTrue(r.isRecurring());
        assertFalse(r.explicit);
        assertTrue(r.describe().toLowerCase().contains("checking"));
    }

    @Test
    public void forMonitorHonoursANamedInterval() {
        RecurrenceRequest.Request r = RecurrenceRequest.forMonitor("watch it daily");
        assertTrue(r.explicit);
        assertEquals("Every day", r.repeat.label());
    }

    @Test
    public void thePhraseListIsGoneFromSource() throws IOException {
        Path src = findSource("RecurrenceRequest.java");
        String text = new String(Files.readAllBytes(src), StandardCharsets.UTF_8);
        assertFalse(text.contains("TRACKING_VERBS"));
        assertFalse(text.contains("keep up"));
        assertFalse(text.contains("keep me posted"));
        assertFalse(text.contains("keep me updated"));
        assertFalse(text.contains("stay updated"));
        assertFalse(text.contains("isStopRequest"));
        assertFalse(text.contains("stop tracking"));
    }

    private static Path findSource(String name) {
        Path[] candidates = {
                Paths.get("src/main/java/com/mrnobody/agent/planner/" + name),
                Paths.get("app/android/app/src/main/java/com/mrnobody/agent/planner/" + name),
                Paths.get("../src/main/java/com/mrnobody/agent/planner/" + name),
        };
        for (Path p : candidates) {
            if (Files.isRegularFile(p)) return p;
        }
        throw new AssertionError("could not find " + name + " from " + Paths.get("").toAbsolutePath());
    }
}
