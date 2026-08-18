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
        return build(instruction, sources, pagesRead, null);
    }

    /**
     * @param nonce the fence token when sources are wrapped by
     *              {@link UntrustedContent}, or null for unfenced text
     */
    public static String build(String instruction, String sources, boolean pagesRead,
                               String nonce) {
        StringBuilder sb = new StringBuilder();

        // The user's question comes first and is named as theirs, so the model
        // has already been told who it works for before it reads any page.
        sb.append("Question from the user:\n").append(instruction).append("\n\n");

        sb.append(pagesRead
                ? "Below are the pages that were fetched for it. "
                : "Below are search result summaries — the pages themselves could not be read. ");
        sb.append("Use ONLY what is written below.\n");

        if (nonce != null && !nonce.isEmpty()) {
            sb.append("\n").append(UntrustedContent.rules(nonce));
        }

        sb.append("\nRules:\n")
                .append("- Cite every claim with its source number, like [1].\n")
                .append("- If the sources do not answer the question, say exactly what is missing. ")
                .append("Do not fill the gap from memory.\n")
                .append("- Do not name a source that is not listed below.\n")
                .append("- Prefer fewer, supported statements over a complete-looking answer.\n")
                .append("\nSources:\n").append(sources);

        return sb.toString();
    }
}
