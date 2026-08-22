package com.mrnobody.agent.skills;

import java.util.Set;

/** Top-level capability route, above research query shaping and raw tools. */
public interface SkillDefinition {
    String id();
    int score(String instruction);
    String executionPlatform();
    Set<String> toolScope();
}
