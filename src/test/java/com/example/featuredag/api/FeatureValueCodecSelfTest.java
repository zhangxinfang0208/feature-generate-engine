package com.example.featuredag.api;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.definition.EntityScope;
import com.example.featuredag.definition.FeatureDefinition;
import com.example.featuredag.expression.ExpressionParser;
import com.example.featuredag.logical.LogicalDag;
import com.example.featuredag.logical.LogicalDagBuilder;
import com.example.featuredag.logical.ValueShape;
import com.example.featuredag.operator.OperatorRegistry;
import com.example.featuredag.runtime.ListSequenceValue;
import com.example.featuredag.runtime.ScalarValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class FeatureValueCodecSelfTest {
    private FeatureValueCodecSelfTest() {}

    public static void run() {
        List<FeatureDefinition> definitions = List.of(
                FeatureDefinition.builder()
                        .name("request_time")
                        .role(com.example.featuredag.definition.FeatureRole.RAW)
                        .dataType(DataType.INT)
                        .addEntityScope(EntityScope.SCENE)
                        .sourceBinding("request_time")
                        .declaredValueShape(ValueShape.SCALAR)
                        .build(),
                FeatureDefinition.builder()
                        .name("ratings")
                        .role(com.example.featuredag.definition.FeatureRole.RAW)
                        .dataType(DataType.INT)
                        .addEntityScope(EntityScope.USER)
                        .sourceBinding("ratings")
                        .declaredValueShape(ValueShape.SEQUENCE)
                        .build(),
                FeatureDefinition.builder()
                        .name("category")
                        .role(com.example.featuredag.definition.FeatureRole.RAW)
                        .dataType(DataType.STRING)
                        .addEntityScope(EntityScope.ITEM)
                        .sourceBinding("category")
                        .declaredValueShape(ValueShape.SCALAR)
                        .build(),
                FeatureDefinition.builder()
                        .name("payload")
                        .role(com.example.featuredag.definition.FeatureRole.RAW)
                        .dataType(DataType.OBJECT)
                        .addEntityScope(EntityScope.SCENE)
                        .sourceBinding("payload")
                        .declaredValueShape(ValueShape.OBJECT)
                        .build());
        LogicalDag dag = new LogicalDagBuilder(
                new ExpressionParser(), OperatorRegistry.standard())
                .build(definitions, Set.of("request_time", "ratings", "category", "payload"));

        FeatureInputDecoder decoder = FeatureInputDecoder.from(dag);
        Map<String, Object> offline = decoder.decodeOffline(Map.of(
                "request_time", List.of(100L),
                "ratings", List.of(1L, 0L, 1L),
                "ignored", List.of("unused")));
        assert offline.equals(Map.of(
                "request_time", 100L,
                "ratings", List.of(1L, 0L, 1L))) : offline;

        Map<String, Object> shared = decoder.decodeOnlineShared(Map.of(
                "request_time", List.of(100L),
                "ratings", List.of(1L, 0L, 1L)));
        assert !shared.containsKey("category") : shared;
        List<Map<String, Object>> candidates = decoder.decodeOnlineCandidates(List.of(
                Map.of("category", List.of("tech")),
                Map.of("category", List.of("sports"))));
        assert candidates.equals(List.of(
                Map.of("category", "tech"),
                Map.of("category", "sports"))) : candidates;

        FeatureOutputEncoder encoder = FeatureOutputEncoder.from(dag);
        List<Object> nullable = new ArrayList<>();
        nullable.add(null);
        List<?> nullOutput = encoder.encode("request_time", new ScalarValue(null));
        assert nullOutput.size() == 1 && nullOutput.getFirst() == null : nullOutput;
        assert encoder.encode(
                "ratings", new ListSequenceValue("codec-test", List.of(1L, 0L)))
                .equals(List.of(1L, 0L));
        assert encoder.encode(
                "payload", new ScalarValue(List.of("nested")))
                .equals(List.of(List.of("nested")));

        List<?> copied = FeatureValueCollections.immutableList(nullable);
        nullable.set(0, "changed");
        assert copied.getFirst() == null : copied;
        try {
            copied.add(null);
            throw new AssertionError("Copied feature values must be immutable");
        } catch (UnsupportedOperationException expected) {
            // Expected: codec boundaries return immutable copies.
        }
    }
}
