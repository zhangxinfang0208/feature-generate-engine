package com.example.featuredag.logical;

public final class DagBuildException extends RuntimeException {
    public DagBuildException(String message) {
        super(message);
    }

    public DagBuildException(String message, Throwable cause) {
        super(message, cause);
    }
}
