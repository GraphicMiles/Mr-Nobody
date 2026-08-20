package com.mrnobody.agent.planner;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ExtractiveAnswerTest {

    @Test
    public void pagesReadYieldCitedSentencesNotASearchDump() {
        String sources = "\n[1] Bitcoin price\nhttps://example.com/btc\n"
                + "Bitcoin traded at 64000 dollars on Tuesday. Analysts said demand was steady.\n";
        String answer = ExtractiveAnswer.compose("what is the bitcoin price", sources, true, null);
        assertTrue(answer, answer.contains("64000"));
        assertTrue(answer, answer.contains("[1]"));
        assertTrue(answer, answer.contains("No language model was used"));
        assertFalse(answer, answer.startsWith("Search results for"));
    }

    @Test
    public void headingDropsResearchDirectivesAndKeepsTheSubject() {
        String heading = ExtractiveAnswer.heading(
                "Research why the sky appears blue. Use at least two reliable sources "
                        + "and include citations.");
        assertTrue(heading, heading.equals("Why the sky appears blue"));
        assertFalse(heading, heading.contains("include citations"));
    }

    @Test
    public void scriptConfigurationCannotBecomeAnAnswerSentence() {
        String sources = "\n[1] YouTube\nhttps://youtube.com/watch?v=x\n"
                + "(function() { window.ytplayer={}; ytcfg.set({\"EXPERIMENT_FLAGS\":"
                + "{\"ab_det_apm\":true}}); })();.\n"
                + "ScreenCrush published a video explaining the latest film news today.\n";
        String answer = ExtractiveAnswer.compose(
                "latest ScreenCrush video", sources, true, null);
        assertFalse(answer, answer.contains("EXPERIMENT_FLAGS"));
        assertFalse(answer, answer.contains("window.ytplayer"));
        assertTrue(answer, answer.contains("ScreenCrush published"));
    }

    @Test
    public void unreadPagesAreLabelledAsListingsNotAnAnswer() {
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("title", "Some page");
        row.put("url", "https://example.com/a");
        row.put("snippet", "A snippet.");
        rows.add(row);
        String answer = ExtractiveAnswer.compose("find laptops", "", false, rows);
        assertTrue(answer, answer.contains("search listings, not an answer"));
        assertTrue(answer, answer.contains("Some page"));
        assertFalse(answer, answer.contains("Search results for"));
    }

    @Test
    public void nothingReadSaysSo() {
        String answer = ExtractiveAnswer.compose("hello", "", false, null);
        assertTrue(answer, answer.contains("Nothing was read"));
    }

    @Test
    public void mirrorPagesDoNotRepeatTheSameAnswerThreeTimes() {
        // BUG-9: three URL variants of one spec produced the same sentence
        // three times under three citation numbers.
        String body = "The HTML standard defines the core language of the web platform. "
                + "It is maintained by the WHATWG as a living standard with regular updates.";
        String sources = "\n[1] HTML Standard\nhttps://html.spec.whatwg.org/\n" + body + "\n"
                + "\n[2] HTML Standard (one page)\nhttps://html.spec.whatwg.org/multipage/\n"
                + body + "\n"
                + "\n[3] HTML Standard (dev)\nhttps://html.spec.whatwg.org/dev/\n" + body + "\n";
        String answer = ExtractiveAnswer.compose(
                "what is the html standard", sources, true, null);
        assertTrue(answer, answer.contains("[1]"));
        assertFalse(answer, answer.contains("[2]"));
        assertFalse(answer, answer.contains("[3]"));
        int first = answer.indexOf("core language of the web platform");
        assertTrue(answer, first >= 0);
        assertTrue(answer, answer.indexOf("core language of the web platform", first + 1) < 0);
    }

    @Test
    public void sharedBoilerplateSentencesAreNotCitedTwice() {
        String sources = "\n[1] Site A\nhttps://a.example.com/page\n"
                + "Rayleigh scattering makes the sky appear blue during the day. "
                + "Sunsets appear red because longer wavelengths dominate at low angles.\n"
                + "\n[2] Site B\nhttps://b.example.com/page\n"
                + "Rayleigh scattering makes the sky appear blue during the day. "
                + "Blue light is scattered more strongly than red light by air molecules.\n";
        String answer = ExtractiveAnswer.compose(
                "why is the sky blue", sources, true, null);
        int first = answer.indexOf("Rayleigh scattering makes the sky appear blue");
        assertTrue(answer, first >= 0);
        assertTrue(answer,
                answer.indexOf("Rayleigh scattering makes the sky appear blue", first + 1) < 0);
    }

    @Test
    public void consentWallBoilerplateCannotBecomeTheAnswer() {
        // BUG-5: "Please enable JavaScript or switch to a supported browser"
        // was quoted as an answer on-device.
        String sources = "\n[1] YouTube\nhttps://youtube.com/watch?v=x\n"
                + "Please enable JavaScript or switch to a supported browser to continue. "
                + "The channel published a new video about electric cars this week.\n";
        String answer = ExtractiveAnswer.compose(
                "latest video about electric cars", sources, true, null);
        assertFalse(answer, answer.contains("supported browser"));
        assertFalse(answer, answer.contains("enable JavaScript"));
        assertTrue(answer, answer.contains("electric cars"));
    }
}
