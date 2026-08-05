package com.example.featuredag.expression;

public record SourceSpan(int startOffset, int endOffset) {
    public SourceSpan {
        if (startOffset < 0 || endOffset < startOffset) {
            throw new IllegalArgumentException("Invalid source span: " + startOffset + ".." + endOffset);
        }
    }
}
