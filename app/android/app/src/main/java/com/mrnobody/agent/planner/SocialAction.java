package com.mrnobody.agent.planner;

import com.mrnobody.agent.core.ImpactKind;
import com.mrnobody.agent.core.Tier;

/**
 * Account-affecting verbs, mapped onto existing tools — not unofficial APIs.
 *
 * <p>Read notifications stays READ. Draft is WRITE. Send / publish / delete
 * confirm. The browser + a grant do the work.
 */
public final class SocialAction {

    public final String verb;
    public final ImpactKind impact;
    public final Tier tier;

    private SocialAction(String verb, ImpactKind impact, Tier tier) {
        this.verb = verb;
        this.impact = impact;
        this.tier = tier;
    }

    public static SocialAction classify(String text) {
        if (text == null) return null;
        String t = text.toLowerCase();
        if (contains(t, "delete post", "delete tweet", "delete the tweet",
                "delete the post", "unsend", "remove the post")) {
            return new SocialAction("delete", ImpactKind.DELETE, Tier.EXEC);
        }
        if (contains(t, "publish", "post this", "tweet this", "share this")) {
            return new SocialAction("publish", ImpactKind.PUBLISH, Tier.EXEC);
        }
        if (contains(t, "send the reply", "send message", "send dm", "submit the reply")) {
            return new SocialAction("send", ImpactKind.SEND, Tier.EXEC);
        }
        if (contains(t, "draft", "write a reply", "compose")) {
            return new SocialAction("draft", ImpactKind.DRAFT, Tier.WRITE);
        }
        if (contains(t, "notifications", "check messages", "inbox", "mentions")) {
            return new SocialAction("read", ImpactKind.OBSERVE, Tier.READ);
        }
        return null;
    }

    private static boolean contains(String hay, String... needles) {
        for (String n : needles) {
            if (hay.contains(n)) return true;
        }
        return false;
    }
}
