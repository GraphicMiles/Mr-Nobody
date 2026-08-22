package com.mrnobody.agent.planner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The device-local arithmetic fast path: exact, no model, narrow by design. */
public class CalculatorSkillTest {

    private static String firstLine(String answer) {
        return answer.contains("\n\n") ? answer.split("\n\n")[0] : answer;
    }

    @Test
    public void answersPercentOf() {
        String a = CalculatorSkill.answer("what is 25% of 800");
        assertNotNull(a);
        assertEquals("200", firstLine(a));
    }

    @Test
    public void answersPercentOfVariouslyPhrased() {
        assertEquals("200", firstLine(CalculatorSkill.answer("25% of 800")));
        assertEquals("10", firstLine(CalculatorSkill.answer("20% of 50")));
    }

    @Test
    public void answersBasicArithmetic() {
        assertEquals("4", firstLine(CalculatorSkill.answer("what is 2 + 2")));
        assertEquals("56", firstLine(CalculatorSkill.answer("8 times 7")));
        assertEquals("56", firstLine(CalculatorSkill.answer("8 x 7")));
        assertEquals("56", firstLine(CalculatorSkill.answer("8*7")));
        assertEquals("7", firstLine(CalculatorSkill.answer("10 - 3")));
        assertEquals("25", firstLine(CalculatorSkill.answer("100 / 4")));
    }

    @Test
    public void respectsPrecedenceAndParentheses() {
        assertEquals("20", firstLine(CalculatorSkill.answer("(2 + 3) * 4")));
        assertEquals("14", firstLine(CalculatorSkill.answer("2 + 3 * 4")));
    }

    @Test
    public void answerCitesNoModel() {
        String a = CalculatorSkill.answer("what is 25% of 800");
        assertTrue(a, a.contains("no language model was used"));
    }

    @Test
    public void rejectsProseThatIsNotArithmetic() {
        assertNull(CalculatorSkill.answer("what is the population of Nigeria"));
        assertNull(CalculatorSkill.answer("who is the president"));
        assertNull(CalculatorSkill.answer("what is the meaning of life"));
    }

    @Test
    public void rejectsABareNumberAndNonFinite() {
        assertNull(CalculatorSkill.answer("2026"));
        assertNull(CalculatorSkill.answer("what is 10 / 0"));
        assertNull(CalculatorSkill.answer(""));
        assertNull(CalculatorSkill.answer(null));
    }

    @Test
    public void handlesDecimalsAndSigns() {
        assertEquals("2.5", firstLine(CalculatorSkill.answer("10 / 4")));
        assertEquals("-3", firstLine(CalculatorSkill.answer("2 - 5")));
        assertEquals("1000", firstLine(CalculatorSkill.answer("1,000 + 0")));
    }
}
