package com.example.featuredag.api;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.definition.EntityScope;
import com.example.featuredag.definition.FeatureDefinition;
import com.example.featuredag.definition.FeatureRole;
import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.expression.ExpressionParser;
import com.example.featuredag.logical.LogicalDag;
import com.example.featuredag.logical.LogicalDagBuilder;
import com.example.featuredag.operator.OperatorRegistry;

import org.junit.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

/** 公共 List 输入契约在 BIGINT 源边界统一产出 Long，并拒绝小数与 64 位溢出。 */
public class BigintFeatureInputTest {

    @Test
    public void scalarAndSequenceInputsNormalizeToLong() {
        FeatureInputDecoder decoder = decoder();

        Map<String, Object> decoded = decoder.decodeOffline(Map.of(
                "wide_id", List.of(7),
                "wide_ids", List.of(1, 2147483648L)));

        assertEquals(Long.valueOf(7L), decoded.get("wide_id"));
        assertEquals(List.of(1L, 2147483648L), decoded.get("wide_ids"));
    }

    @Test
    public void invalidBigintInputsAreRejectedAtApiBoundary() {
        FeatureInputDecoder decoder = decoder();

        assertThrows(
                IllegalArgumentException.class,
                () -> decoder.decodeOffline(Map.of("wide_id", List.of(1.5))));
        assertThrows(
                IllegalArgumentException.class,
                () -> decoder.decodeOffline(Map.of(
                        "wide_id", List.of(new BigInteger("9223372036854775808")))));
        assertThrows(
                IllegalArgumentException.class,
                () -> decoder.decodeOffline(Map.of("wide_ids", List.of(1L, "2"))));
    }

    private static FeatureInputDecoder decoder() {
        FeatureDefinition scalar = FeatureDefinition.raw(
                "wide_id", DataType.BIGINT, EntityScope.USER, null);
        FeatureDefinition sequence = FeatureDefinition.builder()
                .name("wide_ids")
                .role(FeatureRole.RAW)
                .dataType(DataType.BIGINT)
                .addEntityScope(EntityScope.USER)
                .sourceBinding("wide_ids")
                .declaredValueShape(ValueShape.SEQUENCE)
                .build();
        LogicalDag dag = new LogicalDagBuilder(
                new ExpressionParser(), OperatorRegistry.standard())
                .build(List.of(scalar, sequence), Set.of("wide_id", "wide_ids"));
        return FeatureInputDecoder.from(dag);
    }
}
