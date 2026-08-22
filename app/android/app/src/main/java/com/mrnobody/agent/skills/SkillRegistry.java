package com.mrnobody.agent.skills;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Ordered, deterministic top-level skill registry. */
public final class SkillRegistry {

    private static final SkillRegistry STANDARD = new SkillRegistry(Arrays.asList(
            new DesignSkill(), new ClockRoute(), new ResearchRoute()));

    private final List<SkillDefinition> skills;

    public SkillRegistry(List<SkillDefinition> skills) {
        this.skills = Collections.unmodifiableList(new ArrayList<>(skills));
    }

    public static SkillRegistry standard() { return STANDARD; }

    public SkillMatch route(String instruction) {
        SkillDefinition winner = null;
        int best = Integer.MIN_VALUE;
        for (SkillDefinition skill : skills) {
            int score = skill.score(instruction == null ? "" : instruction);
            if (score > best) {
                best = score;
                winner = skill;
            }
        }
        return new SkillMatch(winner == null ? new ResearchRoute() : winner, best);
    }

    private static final class DesignSkill implements SkillDefinition {
        private static final String[] OBJECTS = {
                "design", "poster", "flyer", "presentation", "slide deck", "social post",
                "instagram post", "banner", "brochure", "invitation", "thumbnail", "logo",
                "canva"
        };
        private static final String[] ACTIONS = {
                "create", "make", "generate", "edit", "change", "revise",
                "resize", "export", "download", "approve", "finalize", "publish"
        };

        @Override public String id() { return "design.session"; }
        @Override public String executionPlatform() { return "canva-mcp"; }
        @Override public Set<String> toolScope() {
            return Collections.unmodifiableSet(new LinkedHashSet<>(Collections.singleton("design")));
        }
        @Override public int score(String instruction) {
            String text = instruction.toLowerCase(Locale.ROOT);
            boolean object = contains(text, OBJECTS);
            boolean action = contains(text, ACTIONS) || text.startsWith("design ");
            if (text.contains("canva")) return action ? 100 : 70;
            return object && action ? 90 : -100;
        }
    }

    private static final class ClockRoute implements SkillDefinition {
        @Override public String id() { return "device.clock"; }
        @Override public String executionPlatform() { return "local-device"; }
        @Override public Set<String> toolScope() { return Collections.emptySet(); }
        @Override public int score(String instruction) {
            String text = instruction.toLowerCase(Locale.ROOT).trim();
            return text.matches(".*\\b(time|date|day of the week|what day)\\b.*") ? 60 : -50;
        }
    }

    private static final class ResearchRoute implements SkillDefinition {
        @Override public String id() { return "web.research"; }
        @Override public String executionPlatform() { return "local-device"; }
        @Override public Set<String> toolScope() {
            return Collections.unmodifiableSet(new LinkedHashSet<>(
                    Arrays.asList("search", "http", "browser")));
        }
        @Override public int score(String instruction) { return 0; }
    }

    private static boolean contains(String text, String[] words) {
        for (String word : words) if (text.contains(word)) return true;
        return false;
    }
}
