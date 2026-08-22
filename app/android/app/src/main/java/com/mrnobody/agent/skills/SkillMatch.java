package com.mrnobody.agent.skills;

import java.util.Collections;
import java.util.Set;

public final class SkillMatch {
    public final String id;
    public final String executionPlatform;
    public final Set<String> toolScope;
    public final int score;

    SkillMatch(SkillDefinition skill, int score) {
        this.id = skill.id();
        this.executionPlatform = skill.executionPlatform();
        this.toolScope = Collections.unmodifiableSet(skill.toolScope());
        this.score = score;
    }

    public boolean isDesign() { return id.startsWith("design."); }
    public boolean isClock() { return "device.clock".equals(id); }
}
