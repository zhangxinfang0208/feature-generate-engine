package com.example.featuredag.expression;

public sealed interface AstNode permits AstFeatureRef, AstLiteral, AstObjectLiteral, AstCall {
    SourceSpan sourceSpan();
}
