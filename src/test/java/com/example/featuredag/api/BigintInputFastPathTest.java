package com.example.featuredag.api;

import com.example.featuredag.definition.*;
import com.example.featuredag.expression.ExpressionParser;
import com.example.featuredag.logical.LogicalDagBuilder;
import com.example.featuredag.operator.OperatorRegistry;
import org.junit.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

public class BigintInputFastPathTest {
    @Test
    public void existingLongsKeepTheirIdentityWithoutReboxing() {
        Long value = Long.valueOf(9_000_000_001L);
        Map<String, Object> decoded = decoder().decodeOffline(Map.of("scalar", List.of(value),
                "sequence", List.of(value, Long.MIN_VALUE, Long.MAX_VALUE)));
        assertSame(value, decoded.get("scalar"));
        List<?> sequence = (List<?>) decoded.get("sequence");
        assertSame(value, sequence.get(0));
        assertEquals(List.of(value, Long.MIN_VALUE, Long.MAX_VALUE), sequence);
    }

    @Test
    public void mixedNumericInputsStayExactAndDefensivelyCopied() {
        List<Number> input = new ArrayList<>(Arrays.asList((byte) 1, (short) 2, 3, 4L,
                new BigInteger("9223372036854775807"), new BigDecimal("6.000"), 7.0, 8.0f));
        List<?> result = (List<?>) decoder().decodeOffline(Map.of("sequence", input)).get("sequence");
        input.clear();
        assertEquals(List.of(1L, 2L, 3L, 4L, Long.MAX_VALUE, 6L, 7L, 8L), result);
        assertThrows(UnsupportedOperationException.class, result::clear);
    }

    @Test
    public void fractionsNonFiniteNumbersAndOverflowRemainRejected() {
        for (Object invalid : Arrays.asList(1.25, Float.NaN, Double.POSITIVE_INFINITY,
                new BigInteger("9223372036854775808"), new BigInteger("-9223372036854775809"),
                new BigDecimal("2.0001"), "3", null)) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> decoder().decodeOffline(Map.of("sequence", Arrays.asList(1L, invalid))));
            assertTrue(failure.getMessage().contains("sequence at index 1"));
        }
    }

    private static FeatureInputDecoder decoder() {
        FeatureDefinition sequence = FeatureDefinition.builder().name("sequence").role(FeatureRole.RAW)
                .dataType(DataType.BIGINT).addEntityScope(EntityScope.USER).sourceBinding("sequence")
                .declaredValueShape(ValueShape.SEQUENCE).build();
        return FeatureInputDecoder.from(new LogicalDagBuilder(new ExpressionParser(), OperatorRegistry.standard())
                .build(List.of(sequence, FeatureDefinition.raw("scalar", DataType.BIGINT, EntityScope.USER, null)),
                        Set.of("scalar", "sequence")));
    }
}
