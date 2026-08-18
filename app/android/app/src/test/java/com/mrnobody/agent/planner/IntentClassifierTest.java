package com.mrnobody.agent.planner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.mrnobody.agent.ai.AiProvider;
import com.mrnobody.agent.ai.LocalProvider;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * The classifier is a model call that emits one of three labels. These tests
 * cover the contract around that call: parse, fail-closed, no seed phrases
 * in the prompt. Paraphrase behaviour is in {@link IntentClassifierParaphraseTest}.
 */
public class IntentClassifierTest {

    @Test
    public void jsonIntentIsRead() {
        assertEquals(TaskIntent.RECURRING_MONITOR,
                IntentClassifier.parseIntent("{\"intent\":\"recurring_monitor\"}"));
        assertEquals(TaskIntent.NAMED_SOURCE_FETCH,
                IntentClassifier.parseIntent("{\"intent\":\"named_source_fetch\"}"));
        assertEquals(TaskIntent.ONE_TIME_ANSWER,
                IntentClassifier.parseIntent("{\"intent\":\"one_time_answer\"}"));
    }

    @Test
    public void messyModelOutputStillParses() {
        assertEquals(TaskIntent.RECURRING_MONITOR,
                IntentClassifier.parseIntent(
                        "```json\n{\"intent\":\"recurring_monitor\"}\n```"));
        assertEquals(TaskIntent.NAMED_SOURCE_FETCH,
                IntentClassifier.parseIntent("named_source_fetch"));
        assertEquals(TaskIntent.ONE_TIME_ANSWER,
                IntentClassifier.parseIntent("{\"intent\":\"one-time-answer\"}"));
    }

    @Test
    public void garbageIsNotAGuess() {
        assertNull(IntentClassifier.parseIntent("sure, I can help with that"));
        assertNull(IntentClassifier.parseIntent(""));
        assertNull(IntentClassifier.parseIntent(null));
        assertNull(IntentClassifier.parseIntent("{\"intent\":\"watch_forever\"}"));
    }

    @Test
    public void aLocalProviderDoesNotScheduleByGuessing() {
        IntentClassifier.Decision d =
                IntentClassifier.classify(new LocalProvider(),
                        "let me know if anything comes up about Marvel");
        assertEquals(TaskIntent.ONE_TIME_ANSWER, d.intent);
        assertFalse(d.fromModel);
    }

    @Test
    public void aRemoteModelDecisionIsHonoured() {
        Scripted p = new Scripted("{\"intent\":\"recurring_monitor\"}");
        IntentClassifier.Decision d = IntentClassifier.classify(p,
                "ping me about new studio news");
        assertEquals(TaskIntent.RECURRING_MONITOR, d.intent);
        assertTrue(d.fromModel);
        assertTrue(p.users.get(0).contains("ping me about new studio news"));
        assertTrue(p.users.get(0).contains("one_time_answer"));
        assertTrue(p.users.get(0).contains("recurring_monitor"));
        assertTrue(p.users.get(0).contains("named_source_fetch"));
    }

    @Test
    public void theRubricDoesNotMemoriseTheOldBugReports() {
        String prompt = IntentClassifier.USER_RUBRIC + IntentClassifier.SYSTEM_PROMPT
                + IntentClassifier.CANCEL_RUBRIC;
        String lower = prompt.toLowerCase();
        assertFalse(lower.contains("keep up"));
        assertFalse(lower.contains("keep me posted"));
        assertFalse(lower.contains("keep me updated"));
        assertFalse(lower.contains("stay updated"));
        assertFalse(lower.contains("stay up to date"));
        assertFalse(lower.contains("nkiri"));
        assertFalse(lower.contains("marvel"));
        assertFalse(lower.contains("stop tracking"));
        assertFalse(lower.contains("stop monitoring"));
    }

    @Test
    public void cancelIsFailClosed() {
        assertFalse(IntentClassifier.parseCancel(null));
        assertFalse(IntentClassifier.parseCancel("no idea"));
        assertFalse(IntentClassifier.parseCancel("{\"cancel\":false}"));
        assertTrue(IntentClassifier.parseCancel("{\"cancel\": true}"));
        assertTrue(IntentClassifier.wantsCancel(
                new Scripted("{\"cancel\":true}"), "that's enough, drop it"));
        assertFalse(IntentClassifier.wantsCancel(
                new LocalProvider(), "that's enough, drop it"));
    }

    @Test
    public void cancelParaphrasesAreAskedNotScanned() {
        String[] phrasings = {
                "that's enough",
                "you can drop this",
                "no more updates",
                "I don't need this anymore",
                "we're done watching",
        };
        for (String p : phrasings) {
            Scripted yes = new Scripted("{\"cancel\":true}");
            assertTrue(p, IntentClassifier.wantsCancel(yes, p));
            assertTrue(yes.users.get(0).contains(p));
            assertFalse(RecurrenceRequest.parse(p).isRecurring());
        }
    }

    /** Remote enough to be asked; returns one canned completion. */
    static final class Scripted implements AiProvider {
        final List<String> users = new ArrayList<String>();
        final String reply;

        Scripted(String reply) {
            this.reply = reply;
        }

        @Override public String id() { return "scripted"; }
        @Override public String displayName() { return "Scripted"; }
        @Override public boolean isRemote() { return true; }

        @Override
        public void complete(String system, String user, CompletionCallback cb) {
            users.add(user);
            cb.onResult(reply);
        }
    }
}
