package com.mrnobody.agent.resilience;

/** Stable failure categories shared by tools, AI providers, MCP, and workers. */
public enum FailureKind {
    TRANSIENT_NETWORK,
    RATE_LIMIT,
    TIMEOUT,
    AUTHENTICATION,
    QUOTA,
    CONFIGURATION,
    VALIDATION,
    UNSUPPORTED,
    SAFETY,
    CANCELLED,
    AMBIGUOUS,
    PERMANENT,
    UNKNOWN
}
