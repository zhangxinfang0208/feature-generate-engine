package com.example.featuredag.operator;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.definition.EntityScope;
import com.example.featuredag.definition.FeatureDefinition;
import com.example.featuredag.definition.OutputPolicy;
import com.example.featuredag.expression.ExpressionParser;
import com.example.featuredag.logical.DagBuildException;
import com.example.featuredag.logical.LogicalDagBuilder;
import org.junit.Test;

import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * 结构化事件算子的构图期拒绝（JUnit 4）：
 * calc_delta_seq / zip_concat 在 infer 阶段拒绝 EVENT_SEQUENCE（空序列也能失败），
 * 运行时元素检查保留为防御。
 */
public final class StructuredEventOperatorsRejectionTest {
    @Test
    public void calcDeltaSeqAndZipConcatRejectedAtBuild() {
        OperatorRegistry registry = OperatorRegistry.standard();
        List<FeatureDefinition> deltaDefinitions = List.of(
                FeatureDefinition.raw(
                        "events", DataType.EVENT_SEQUENCE, EntityScope.USER, null),
                FeatureDefinition.derived(
                        "delta_bad",
                        DataType.DOUBLE,
                        "calc_delta_seq(events, 1)",
                        OutputPolicy.OUTPUT));
        DagBuildException deltaFailure = assertThrows(
                DagBuildException.class,
                () -> new LogicalDagBuilder(new ExpressionParser(), registry)
                        .build(deltaDefinitions, Set.of("delta_bad")));
        assertTrue(deltaFailure.getMessage().contains("calc_delta_seq"));

        List<FeatureDefinition> zipDefinitions = List.of(
                FeatureDefinition.raw(
                        "events", DataType.EVENT_SEQUENCE, EntityScope.USER, null),
                FeatureDefinition.derived(
                        "zip_bad",
                        DataType.STRING,
                        "zip_concat(events, events)",
                        OutputPolicy.OUTPUT));
        DagBuildException zipFailure = assertThrows(
                DagBuildException.class,
                () -> new LogicalDagBuilder(new ExpressionParser(), registry)
                        .build(zipDefinitions, Set.of("zip_bad")));
        assertTrue(zipFailure.getMessage().contains("zip_concat"));
    }
}
