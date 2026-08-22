package com.mrnobody.agent.design;

/** Independent gates; approving one never implies either of the others. */
public enum ReviewGate {
    NOT_REQUIRED,
    PENDING,
    APPROVED,
    REJECTED
}
