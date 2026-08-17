package com.mrnobody.agent.planner;

/** Classification of a unified-input entry. */
public enum IntentType {
    URL,       // navigate the visible browser
    SEARCH,    // run a search / fetch
    TASK       // an instruction that becomes a persistent task
}
