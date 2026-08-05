package com.example.featuredag.logical;

import java.util.Objects;

public record NodeInput(String nodeId, int inputPort, String argumentName) {
    public NodeInput {
        Objects.requireNonNull(nodeId, "nodeId");
        if (inputPort < 0) throw new IllegalArgumentException("inputPort must be >= 0");
    }

    public static NodeInput positional(String nodeId, int inputPort) {
        return new NodeInput(nodeId, inputPort, null);
    }
}
