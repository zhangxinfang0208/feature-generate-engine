package com.example.featuredag.runtime;

public record SequenceEvent(
        String itemId,
        String industryId,
        long timestamp,
        String eventType,
        double value) {
}
