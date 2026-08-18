package com.mrnobody.agent.planner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.mrnobody.agent.ai.AiProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Arrays;
import java.util.Collection;

/**
 * The bar for "fixed": classification must work on rewordings that were
 * never in a phrase list. These are not the original bug-report strings.
 *
 * <p>A scripted model returns the label a competent reasoner would. The
 * test then checks two things that phrase-matching cannot fake: the
 * production parser has no verb list that would have caught these, and
 * the classifier actually asked the model instead of scanning the text.
 */
@RunWith(Parameterized.class)
public class IntentClassifierParaphraseTest {

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> cases() {
        return Arrays.asList(new Object[][]{
                // recurring_monitor — ongoing awareness, no seed verbs
                {"ping me about new studio news",
                        TaskIntent.RECURRING_MONITOR},
                {"don't want to miss anything they announce",
                        TaskIntent.RECURRING_MONITOR},
                {"if anything comes up on that front, pass it along",
                        TaskIntent.RECURRING_MONITOR},
                {"whenever they drop something I want to hear",
                        TaskIntent.RECURRING_MONITOR},
                {"keep a running eye on price moves from here",
                        TaskIntent.RECURRING_MONITOR},
                {"flag me the next time the listing changes",
                        TaskIntent.RECURRING_MONITOR},

                // named_source_fetch — obtain from a pointed-at site
                {"pull Infinity War off nkiri.ink",
                        TaskIntent.NAMED_SOURCE_FETCH},
                {"the file lives on films.example.org — grab Dune from there",
                        TaskIntent.NAMED_SOURCE_FETCH},
                {"see whether blog.example.com posted the essay and bring it here",
                        TaskIntent.NAMED_SOURCE_FETCH},
                {"open the listing at archive.is/abc123 and save the pdf",
                        TaskIntent.NAMED_SOURCE_FETCH},
                {"get me whatever @StudioHub put up on their page",
                        TaskIntent.NAMED_SOURCE_FETCH},
                {"nkiri.ink has the episode; I want that copy, not a review",
                        TaskIntent.NAMED_SOURCE_FETCH},

                // one_time_answer — now, once
                {"what did they announce yesterday",
                        TaskIntent.ONE_TIME_ANSWER},
                {"how much is bitcoin right now",
                        TaskIntent.ONE_TIME_ANSWER},
                {"who directed Infinity War",
                        TaskIntent.ONE_TIME_ANSWER},
                {"summarize the recap I just pasted",
                        TaskIntent.ONE_TIME_ANSWER},
                {"is it raining in Lagos this afternoon",
                        TaskIntent.ONE_TIME_ANSWER},
                {"define a polar vortex in one paragraph",
                        TaskIntent.ONE_TIME_ANSWER},
        });
    }

    private final String wording;
    private final TaskIntent expected;

    public IntentClassifierParaphraseTest(String wording, TaskIntent expected) {
        this.wording = wording;
        this.expected = expected;
    }

    @Test
    public void aReasoningStepLabelsTheParaphrase() {
        RecordingProvider provider = new RecordingProvider(
                "{\"intent\":\"" + expected.wire() + "\"}");
        IntentClassifier.Decision d = IntentClassifier.classify(provider, wording);
        assertEquals(wording, expected, d.intent);
        assertTrue("the model must actually have been asked", d.fromModel);
        assertEquals(1, provider.asked.size());
        assertTrue(provider.asked.get(0).contains(wording));
    }

    @Test
    public void theOldParserDoesNotSecretlyCatchIt() {
        // An explicit interval is structure and may still parse. None of
        // these paraphrases name one, so a verb list is the only way they
        // would have been recurring — and that list is gone.
        assertFalse("phrase matching must not still be hiding in parse(): " + wording,
                RecurrenceRequest.parse(wording).isRecurring());
    }

    private static final class RecordingProvider implements AiProvider {
        final java.util.List<String> asked = new java.util.ArrayList<String>();
        final String reply;

        RecordingProvider(String reply) {
            this.reply = reply;
        }

        @Override public String id() { return "scripted"; }
        @Override public String displayName() { return "Scripted"; }
        @Override public boolean isRemote() { return true; }

        @Override
        public void complete(String system, String user, CompletionCallback cb) {
            asked.add(user);
            cb.onResult(reply);
        }
    }
}
