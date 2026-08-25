package com.example.featuredag.definition;

public enum DataType {
    INT,
    BIGINT,
    DOUBLE,
    STRING,
    BOOLEAN,
    OBJECT,
    EVENT_SEQUENCE,
    UNKNOWN;

    public boolean isNumeric() {
        return this == INT || this == BIGINT || this == DOUBLE;
    }
}
