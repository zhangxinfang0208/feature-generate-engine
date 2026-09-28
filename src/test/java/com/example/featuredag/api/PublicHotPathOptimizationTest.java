package com.example.featuredag.api;

import com.example.featuredag.config.FeatureOutputDescriptor;
import com.example.featuredag.definition.*;
import com.example.featuredag.expression.ExpressionParser;
import com.example.featuredag.logical.LogicalDag;
import com.example.featuredag.logical.LogicalDagBuilder;
import com.example.featuredag.operator.OperatorRegistry;
import com.example.featuredag.operator.builtin.SliceByIndicesOperator;
import com.example.featuredag.runtime.ExternalValueMaterializer;
import org.junit.Test;

import java.util.*;
import static org.junit.Assert.*;

public class PublicHotPathOptimizationTest {
    private static LogicalDag dag() {
        FeatureDefinition source = FeatureDefinition.builder().name("seq").role(FeatureRole.RAW)
                .dataType(DataType.INT).addEntityScope(EntityScope.USER).sourceBinding("seq")
                .declaredValueShape(ValueShape.SEQUENCE).build();
        return new LogicalDagBuilder(new ExpressionParser(), OperatorRegistry.standard())
                .build(List.of(source), Set.of("seq"));
    }

    @Test
    public void decodedMissingAndExplicitNullKeepDifferentSemantics() {
        FeatureInputDecoder decoder = FeatureInputDecoder.from(dag());
        assertTrue(decoder.decodeOffline(Map.of()).isEmpty());
        Map<String, List<?>> input = new LinkedHashMap<>();
        input.put("seq", null);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> decoder.decodeOffline(input));
        assertEquals("Feature seq values must not be null", error.getMessage());
        List<Integer> values = new ArrayList<>(Arrays.asList(1, null, 3));
        input.put("seq", values);
        Map<String, Object> decoded = decoder.decodeOffline(input);
        values.clear();
        assertEquals(Arrays.asList(1, null, 3), decoded.get("seq"));
    }

    @Test
    public void resolvedOutputSpecPreservesTruncationPaddingAndIsolation() {
        FeatureOutputEncoder encoder = FeatureOutputEncoder.from(dag(),
                List.of(new FeatureOutputDescriptor("seq", "seq", 0, 0, 3)));
        FeatureOutputEncoder.OutputSpec spec = encoder.requireSpec("seq");
        List<Object> input = new ArrayList<>(Arrays.asList(1, null));
        List<?> result = encoder.encodeValue("seq", input, spec);
        input.clear();
        assertEquals(Arrays.asList(1, null, null), result);
        assertThrows(UnsupportedOperationException.class, result::clear);
        assertEquals(List.of(1, 2, 3), encoder.encodeValue("seq", List.of(1, 2, 3, 4), spec));
        assertEquals("Unknown output feature: missing", assertThrows(NullPointerException.class,
                () -> encoder.requireSpec("missing")).getMessage());
    }

    @Test
    public void materializationKeepsNestedNullsAndSnapshotsSequentialLists() {
        List<Object> nested = new LinkedList<>(Arrays.asList(null, 2));
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("first", nested);
        map.put("second", null);
        List<?> result = (List<?>) new ExternalValueMaterializer()
                .materializeRaw(new LinkedList<>(Arrays.asList(map, null)));
        nested.clear();
        map.clear();
        Map<?, ?> frozen = (Map<?, ?>) result.get(0);
        assertEquals(List.of("first", "second"), new ArrayList<>(frozen.keySet()));
        assertEquals(Arrays.asList(null, 2), frozen.get("first"));
        assertNull(result.get(1));
        assertThrows(UnsupportedOperationException.class, result::clear);
        assertThrows(UnsupportedOperationException.class, frozen::clear);
        assertThrows(UnsupportedOperationException.class, ((List<?>) frozen.get("first"))::clear);
    }

    @Test
    public void slicePreservesDuplicateIndicesNullsBoundsAndIndependentResults() {
        SliceByIndicesOperator operator = new SliceByIndicesOperator();
        List<Object> source = new ArrayList<>(Arrays.asList(7, null, 9));
        List<?> result = (List<?>) operator.evaluate(List.of(source, List.of(2, 1, 2)));
        source.clear();
        assertEquals(Arrays.asList(9, null, 9), result);
        assertThrows(UnsupportedOperationException.class, result::clear);
        assertThrows(IllegalArgumentException.class,
                () -> operator.evaluate(List.of(List.of(7), List.of(0.5))));
        assertThrows(IllegalArgumentException.class,
                () -> operator.evaluate(List.of(List.of(7), List.of(1))));
    }
}
