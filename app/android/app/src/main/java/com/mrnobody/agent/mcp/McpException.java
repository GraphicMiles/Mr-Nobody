package com.mrnobody.agent.mcp;

import com.mrnobody.agent.resilience.OperationFailure;

public final class McpException extends Exception {
    public final OperationFailure failure;

    public McpException(OperationFailure failure) {
        super(failure == null ? "MCP failure" : failure.message);
        this.failure = failure;
    }
}
