package com.mrnobody.agent.planner;

/**
 * How the agent asks a model to answer from sources.
 *
 * <p>Its own class because the wording is the safeguard. The previous prompt
 * said "be concise and factual, do not invent sources" and left the model free
 * to produce a ranked table of restaurants with citations to publications
 * nobody had fetched. Rules a reader can check beat adjectives.
 */
public final class GroundedPrompt {

    private GroundedPrompt() {
    }

    /**
     * @param instruction what the user asked
     * @param sources     numbered sources, already fetched
     * @param pagesRead   true when whole pages were read, false for snippets only
     */
    public static String build(String instruction, String sources, boolean pagesRead) {
        return "Question:\n" + instruction + "\n\n"
                + (pagesRead
                        ? "Below are the pages that were fetched for it. "
                        : "Below are search result summaries — the pages themselves could not be read. ")
                + "Use ONLY what is written below.\n"
                + "\nRules:\n"
                + "- Cite every claim with its source number, like [1].\n"
                + "- If the sources do not answer the question, say exactly what is missing. "
                + "Do not fill the gap from memory.\n"
                + "- Do not name a source that is not listed below.\n"
                + "- Prefer fewer, supported statements over a complete-looking answer.\n"
                + "\nSources:\n" + sources;
    }
}
