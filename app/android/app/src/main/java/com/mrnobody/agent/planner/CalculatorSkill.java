package com.mrnobody.agent.planner;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Device-local arithmetic. A simple "what is 25% of 800" question is a
 * calculation, not a research task: it answers from a tiny parser with no
 * model and no network, turning the most common Tier-0 request into an
 * instant response.
 *
 * <p>Deliberately conservative and narrow. It fires <em>only</em> when the
 * input is an arithmetic expression of numbers and operators (plus a few plain
 * words that are unambiguous), and returns {@code null} for anything that is
 * not clearly a sum. Anything with an alphabetic topic word ("what is the
 * population of Nigeria") falls through to the normal research path, because
 * guessing at arithmetic from prose is exactly the mistake a keyword router
 * makes.
 *
 * <p>This is a computation, never a model call: the result is exact (where a
 * floating point result can be), and the answer carries no language-model
 * claim. Pure Java, JVM-testable, no Android.
 */
public final class CalculatorSkill {

    private CalculatorSkill() {
    }

    /**
     * @return a rendered arithmetic answer, or {@code null} when {@code question}
     *         is not a plain arithmetic request. Never throws.
     */
    public static String answer(String question) {
        if (question == null) return null;
        String expr = normalise(question);
        if (expr == null) return null;
        List<Token> tokens = tokenise(expr);
        if (tokens == null || tokens.isEmpty()) return null;

        // A bare number ("800") is not a question. Require an operator, so
        // prose and plain numbers do not accidentally answer.
        boolean hasOperator = false;
        for (Token t : tokens) {
            if (t.kind == Kind.OP) { hasOperator = true; break; }
        }
        if (!hasOperator) return null;

        Parser parser = new Parser(tokens);
        Double value;
        try {
            value = parser.parse();
        } catch (RuntimeException e) {
            return null;
        }
        if (value == null || !parser.ended() || !Double.isFinite(value)) return null;
        return render(value);
    }

    // ---------------------------------------------------------- tokenizing

    /** Words that unambiguously mean an operator in an arithmetic question. */
    private static String normalise(String question) {
        String s = question.toLowerCase(Locale.ROOT);
        s = s.replace('\u00d7', '*');            // ×
        s = s.replace('\u00f7', '/');            // ÷
        s = s.replace(",", "");                  // 1,000 -> 1000
        s = s.replace("'", "");                  // what's -> whats

        // Multi-word operator phrases before the wrappers: "divided by" is a
        // phrase, and stripping it before the wrapper is harmless either way.
        s = s.replace("multiplied by", " * ");
        s = s.replace("to the power of", " ^ ");
        if (s.indexOf('^') >= 0) return null;    // exponent is deliberately unsupported
        s = s.replaceAll("\\bdivided\\s+by\\b", " / ");

        // Question wrappers, in order of priority. Stripped here, before the
        // single-word operators, so the "of" inside "result of" is never turned
        // into a multiplication on a phrase that is really asking us to compute.
        for (String w : new String[]{
                "what is the result of", "what is the value of", "whats the result of",
                "whats the value of", "how much is", "what is", "what are",
                "calculate", "compute", "solve", "evaluate", "result of",
                "whats", "the value of", "equals"}) {
            if (s.startsWith(w)) {
                s = s.substring(w.length());
                break;
            }
        }

        s = s.replaceAll("\\bdivide\\b", " / ");
        s = s.replaceAll("\\bover\\b", " / ");
        s = s.replaceAll("\\btimes\\b", " * ");
        s = s.replaceAll("\\bmultiply\\b", " * ");
        // "5 x 5" / "5x5" are multiplication images; require a digit on both
        // sides so "x" inside a word (e.g. "explain") is never touched.
        s = s.replaceAll("(?<=\\d)\\s*x\\s*(?=\\d)", " * ");
        s = s.replaceAll("\\bof\\b", " * ");
        s = s.replaceAll("\\bplus\\b", " + ");
        s = s.replaceAll("\\badd\\b", " + ");
        s = s.replaceAll("\\bminus\\b", " - ");
        s = s.replaceAll("\\bsubtract\\b", " - ");
        s = s.replaceAll("\\bpercent\\b", " % ");

        s = s.replace('?', ' ').replace('=', ' ').replace('!', ' ');
        // Keep parentheses: they are part of the expression. Only trim stray
        // whitespace left behind by wrapper stripping.
        s = s.trim();

        // Only arithmetic characters may remain. Any alphabetic topic word
        // means this was never a calculation.
        if (!s.matches("[0-9+.\\-*/().% ]+")) return null;
        if (!s.matches(".*[0-9].*")) return null;
        return s;
    }

    private enum Kind { NUM, OP, END }

    private static final class Token {
        final Kind kind;
        final double num;
        final char op;

        private Token(Kind kind, double num, char op) {
            this.kind = kind;
            this.num = num;
            this.op = op;
        }

        static Token num(double v) { return new Token(Kind.NUM, v, '\0'); }
        static Token op(char c) { return new Token(Kind.OP, 0, c); }
        static final Token END = new Token(Kind.END, 0, '\0');

        boolean isOp(char c) { return kind == Kind.OP && op == c; }
    }

    private static List<Token> tokenise(String s) {
        List<Token> out = new ArrayList<>();
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            if (c >= '0' && c <= '9' || c == '.') {
                int start = i;
                while (i < n && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) i++;
                try {
                    double d = Double.parseDouble(s.substring(start, i));
                    if (!Double.isFinite(d)) return null;
                    out.add(Token.num(d));
                } catch (NumberFormatException e) {
                    return null;
                }
                continue;
            }
            switch (c) {
                case '+': case '-': case '*': case '/': case '%':
                case '(': case ')':
                    out.add(Token.op(c));
                    i++;
                    continue;
                default:
                    return null;
            }
        }
        out.add(Token.END);
        return out;
    }

    // ----------------------------------------------------------- evaluating

    /** Recursive-descent: expr := term (('+'|'-') term)* ; precedence-aware. */
    private static final class Parser {
        private final List<Token> tokens;
        private int pos;

        Parser(List<Token> tokens) {
            this.tokens = tokens;
            this.pos = 0;
        }

        Double parse() {
            Double v = expr();
            if (v == null) return null;
            return v;
        }

        boolean ended() {
            return peek() == Token.END;
        }

        private Double expr() {
            Double v = term();
            if (v == null) return null;
            while (peek().isOp('+') || peek().isOp('-')) {
                char op = peek().op;
                pos++;
                Double rhs = term();
                if (rhs == null) return null;
                v = (op == '+') ? v + rhs : v - rhs;
            }
            return v;
        }

        private Double term() {
            Double v = factor();
            if (v == null) return null;
            while (peek().isOp('*') || peek().isOp('/')) {
                char op = peek().op;
                pos++;
                Double rhs = factor();
                if (rhs == null) return null;
                if (op == '/' && rhs.doubleValue() == 0) return null;
                v = (op == '*') ? v * rhs : v / rhs;
            }
            return v;
        }

        private Double factor() {
            Token t = peek();
            if (t.isOp('(')) {
                pos++;
                Double v = expr();
                if (v == null) return null;
                if (!peek().isOp(')')) return null;
                pos++;
                return v;
            }
            if (t.kind != Kind.NUM) return null;
            pos++;
            double v = t.num;
            // Postfix percent: 25% parses as 0.25, and "of" was already turned
            // into a multiplication, so "25% of 800" is 0.25 * 800.
            while (peek().isOp('%')) {
                pos++;
                v = v / 100.0;
            }
            return v;
        }

        private Token peek() {
            return pos < tokens.size() ? tokens.get(pos) : Token.END;
        }
    }

    private static String render(double value) {
        String rendered;
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            rendered = String.valueOf((long) value);
        } else {
            rendered = String.valueOf(Math.round(value * 1e6) / 1e6);
        }
        return rendered + "\n\nComputed on this device; no language model was used.";
    }
}
