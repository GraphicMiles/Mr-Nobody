package com.mrnobody.agent.planner;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Core answer-quality: a figure question must be answered with a figure, a
 * "who is" question with an identity, and a definition with a definition — not
 * with whatever sentence merely mentions the topic. Also asserts that keyword
 * dumps and menu rails are never quoted, and that the answer is structured
 * (not one glued run of sentences).
 */
public class AnswerQualityTest {

    // -------------------------------------------------------------- figure

    @Test
    public void aPriceQuestionAnswersWithTheFigureNotHashing() {
        // The on-device failure: "what is the bitcoin price" returned the
        // hashing sentence that merely mentions Bitcoin.
        String sources = "\n[1] CoinDesk\nhttps://coindesk.com/btc\n"
                + "Bitcoin is secured with the SHA-256 algorithm, which belongs to the "
                + "SHA-2 family of hashing algorithms. Bitcoin traded at 64000 dollars "
                + "on Tuesday as demand stayed steady.\n"
                + "[2] Investopedia\nhttps://investopedia.com/bitcoin\n"
                + "The price of bitcoin reached an all-time high of 68000 dollars.\n";
        String answer = ExtractiveAnswer.compose("what is the bitcoin price", sources, true, null);
        // The lead must contain a number and the answer's currency/price sense,
        // and must NOT make hashing the headline.
        assertTrue(answer, answer.contains("64000") || answer.contains("68000")
                || answer.contains("all-time high"));
        assertFalse("hash sentence must not be the lead",
                answer.startsWith("# What is the bitcoin price\n\nBitcoin is secured"));
        // The figure is bolded (the key fact).
        assertTrue(answer, answer.contains("**"));
    }

    // -------------------------------------------------------------- person

    @Test
    public void aWhoIsQuestionAnswersWithIdentityAndRejectsMetadataDump() {
        // The on-device failure: the identity answer quoted the keyword dump
        // "Biography, Age, Girlfriend, Family, Career, Net Worth".
        String sources = "\n[1] Wikipedia\nhttps://en.wikipedia.org/wiki/MrBeast\n"
                + "MrBeast Biography, Age, Girlfriend, Family, Career, Net Worth. "
                + "MrBeast is the byname of Jimmy Donaldson, an American YouTuber and "
                + "businessman known for his large-scale challenges, massive giveaways, "
                + "and high-budget philanthropic stunts.\n"
                + "Wikipedia describes MrBeast as the byname of Jimmy Donaldson.\n";
        String answer = ExtractiveAnswer.compose("who is mrbeast", sources, true, null);
        assertTrue(answer, answer.contains("Jimmy Donaldson"));
        assertTrue(answer, answer.contains("YouTuber"));
        assertFalse("the comma-separated label list format must be gone",
                answer.contains("Biography, Age, Girlfriend, Family, Career, Net Worth")
                        || answer.contains("Age, Girlfriend, Family, Career"));
    }

    // ---------------------------------------------------------- definition

    @Test
    public void aDefinitionQuestionAnswersWithTheDefinition() {
        String sources = "\n[1] HTML Standard\nhttps://html.spec.whatwg.org/\n"
                + "The HTML standard defines the core language of the web platform. "
                + "It is maintained by the WHATWG as a living standard with regular "
                + "updates.\n";
        String answer = ExtractiveAnswer.compose("definition of html standard", sources, true, null);
        assertTrue(answer, answer.contains("core language of the web platform"));
        assertTrue(answer, answer.contains("defines"));
    }

    // -------------------------------------------------------- menu / noise

    @Test
    public void aMenuRailIsNeverQuotedAsAnAnswer() {
        // The Yahoo finance nav run that leaked into a price answer.
        String sources = "\n[1] Yahoo Finance\nhttps://finance.yahoo.com/btc\n"
                + "News Sports More News Today's news US Politics World Weather climate "
                + "change Science Originals Newsletters Games Life Health Parenting "
                + "Horoscopes Shopping Food Travel Autos Gift Ideas Buying guides "
                + "Entertainment Celebrity TV Movies Music How to Watch Business Tech "
                + "Market News Your money Personal finance. Bitcoin fell to 59000 dollars "
                + "on Monday, its lowest level this month.\n";
        String answer = ExtractiveAnswer.compose("what is the bitcoin price", sources, true, null);
        assertTrue(answer, answer.contains("59000"));
        assertFalse("the menu rail must not be quoted",
                answer.contains("Horoscopes") || answer.contains("Buying guides")
                        || answer.contains("Gift Ideas"));
    }

    @Test
    public void anUnreadPageAnswerIsStructuredNotGlued() {
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("title", "Some page");
        row.put("url", "https://example.com/a");
        row.put("snippet", "A snippet of text.");
        rows.add(row);
        String answer = ExtractiveAnswer.compose("find laptops", "", false, rows);
        assertTrue(answer, answer.contains("search listings, not an answer"));
    }

    @Test
    public void aFigureLeadIsBoldedAndStructured() {
        String sources = "\n[1] Price site\nhttps://x.example/btc\n"
                + "The current price of bitcoin is 64000 dollars. "
                + "Analysts expect a small rise next week.\n";
        String answer = ExtractiveAnswer.compose("what is the bitcoin price", sources, true, null);
        assertTrue(answer, answer.contains("**64000"));
        // It is structured: a heading, then a lead, then the extractive note.
        assertTrue(answer, answer.startsWith("# What is the bitcoin price"));
        assertTrue(answer, answer.contains("No language model was used"));
    }
}
