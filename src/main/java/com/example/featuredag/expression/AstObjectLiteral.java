package com.example.featuredag.expression;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record AstObjectLiteral(Map<String, AstNode> fields, SourceSpan sourceSpan) implements AstNode {
    public AstObjectLiteral {
        Objects.requireNonNull(fields, "fields");
        Objects.requireNonNull(sourceSpan, "sourceSpan");
        fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }
}
