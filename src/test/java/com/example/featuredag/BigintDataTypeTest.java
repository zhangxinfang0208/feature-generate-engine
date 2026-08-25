package com.example.featuredag;

import com.example.featuredag.config.FeatureConfigLoader;
import com.example.featuredag.config.FeatureConfigMapper;
import com.example.featuredag.config.MappedFeatureSet;
import com.example.featuredag.definition.DataType;
import com.example.featuredag.definition.EntityScope;
import com.example.featuredag.definition.FeatureDefinition;
import com.example.featuredag.definition.FeatureRole;
import com.example.featuredag.definition.OutputPolicy;
import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.expression.ExpressionParser;
import com.example.featuredag.logical.DagBuildException;
import com.example.featuredag.logical.LiteralNode;
import com.example.featuredag.logical.LogicalDag;
import com.example.featuredag.logical.LogicalDagBuilder;
import com.example.featuredag.logical.OperatorNode;
import com.example.featuredag.operator.OperatorRegistry;
import com.example.featuredag.physical.ExecutionEnvironment;
import com.example.featuredag.physical.PhysicalPlan;
import com.example.featuredag.physical.PhysicalPlanner;
import com.example.featuredag.planning.LogicalDagOptimizer;
import com.example.featuredag.runtime.DagRuntime;
import com.example.featuredag.runtime.ExecutionContext;

import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** 独立 BIGINT 类型的配置、字面量、推断、安全提升和运行时载体测试。 */
public class BigintDataTypeTest {

    @Test
    public void configAcceptsBigintAndNormalizesDefaultToLong() {
        String json = """
                {
                  "feature_set_name": "bigint-config",
                  "version": "1",
                  "features": [
                    {
                      "name": "wide_id",
                      "raw_name": "wide_id",
                      "type": "BIGINT",
                      "definition_type": "BASE",
                      "dft": 2147483648,
                      "entity_scopes": ["USER"]
                    },
                    {
                      "name": "wide_output",
                      "type": "BIGINT",
                      "definition_type": "DERIVED",
                      "expression": "wide_id",
                      "output_policy": "OUTPUT"
                    }
                  ]
                }
                """;

        MappedFeatureSet mapped = FeatureConfigMapper.map(
                FeatureConfigLoader.load(json), Set.of(), Map.of());
        FeatureDefinition raw = mapped.definitions().stream()
                .filter(definition -> definition.name().equals("wide_id"))
                .findFirst()
                .orElseThrow();

        assertEquals(DataType.BIGINT, raw.dataType());
        assertEquals(Long.valueOf(2147483648L), raw.defaultValue());

        assertThrows(
                IllegalArgumentException.class,
                () -> FeatureConfigMapper.map(
                        FeatureConfigLoader.load(json.replace("2147483648", "1.5")),
                        Set.of(),
                        Map.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> FeatureConfigMapper.map(
                        FeatureConfigLoader.load(
                                json.replace("2147483648", "9223372036854775808")),
                        Set.of(),
                        Map.of()));
    }

    @Test
    public void largeIntegerLiteralInfersBigintAndExecutesPrecisely() {
        FeatureDefinition maximum = FeatureDefinition.derived(
                "maximum",
                DataType.BIGINT,
                "add(9223372036854775800, 7)",
                OutputPolicy.OUTPUT);
        LogicalDag dag = build(List.of(maximum), Set.of("maximum"));
        OperatorNode add = (OperatorNode) dag.node(dag.featureOutput("maximum").producerNodeId());

        assertEquals(DataType.BIGINT, add.outputType());
        assertTrue(add.inputs().stream()
                .map(input -> dag.node(input.nodeId()))
                .filter(LiteralNode.class::isInstance)
                .map(LiteralNode.class::cast)
                .anyMatch(literal -> literal.outputType() == DataType.BIGINT
                        && literal.value().equals(9223372036854775800L)));

        PhysicalPlan plan = new PhysicalPlanner(OperatorRegistry.standard()).plan(
                new LogicalDagOptimizer(OperatorRegistry.standard()).analyze(dag),
                ExecutionEnvironment.OFFLINE,
                "bigint-literal");
        Object value = new DagRuntime(OperatorRegistry.standard())
                .execute(plan, ExecutionContext.offlineRow("bigint-literal", Map.of()))
                .feature("maximum")
                .raw();
        assertEquals(Long.valueOf(Long.MAX_VALUE), value);
    }

    @Test
    public void featureBoundarySupportsOnlySafeNumericWidening() {
        FeatureDefinition intToBigint = FeatureDefinition.derived(
                "wide", DataType.BIGINT, "5", OutputPolicy.OUTPUT);
        LogicalDag bigintDag = build(List.of(intToBigint), Set.of("wide"));
        assertEquals(DataType.INT,
                bigintDag.node(bigintDag.featureOutput("wide").producerNodeId()).outputType());
        assertEquals(Long.valueOf(5L), execute(bigintDag, "wide"));

        FeatureDefinition bigintToDouble = FeatureDefinition.derived(
                "floating", DataType.DOUBLE, "2147483648", OutputPolicy.OUTPUT);
        assertEquals(Double.valueOf(2147483648.0),
                execute(build(List.of(bigintToDouble), Set.of("floating")), "floating"));

        FeatureDefinition narrowing = FeatureDefinition.derived(
                "narrow", DataType.INT, "2147483648", OutputPolicy.OUTPUT);
        assertThrows(DagBuildException.class, () -> build(List.of(narrowing), Set.of("narrow")));
    }

    @Test
    public void sequenceFeatureBoundaryWidensEveryIntElementToLong() {
        FeatureDefinition numbers = FeatureDefinition.builder()
                .name("numbers")
                .role(FeatureRole.RAW)
                .dataType(DataType.INT)
                .addEntityScope(EntityScope.USER)
                .sourceBinding("numbers")
                .declaredValueShape(ValueShape.SEQUENCE)
                .build();
        FeatureDefinition selected = FeatureDefinition.builder()
                .name("selected")
                .role(FeatureRole.DERIVED)
                .dataType(DataType.BIGINT)
                .expressionContent("slice_by_indices(numbers, [0, 2])")
                .outputPolicy(OutputPolicy.OUTPUT)
                .declaredValueShape(ValueShape.SEQUENCE)
                .build();
        LogicalDag dag = build(List.of(numbers, selected), Set.of("selected"));
        OperatorRegistry registry = OperatorRegistry.standard();
        PhysicalPlan plan = new PhysicalPlanner(registry).plan(
                new LogicalDagOptimizer(registry).analyze(dag),
                ExecutionEnvironment.OFFLINE,
                "bigint-sequence");

        Object value = new DagRuntime(registry)
                .execute(
                        plan,
                        ExecutionContext.offlineRow(
                                "bigint-sequence", Map.of("numbers", List.of(1, 2, 3))))
                .feature("selected")
                .raw();

        assertEquals(List.of(1L, 3L), value);
    }

    private static LogicalDag build(
            List<FeatureDefinition> definitions,
            Set<String> targets) {
        return new LogicalDagBuilder(new ExpressionParser(), OperatorRegistry.standard())
                .build(definitions, targets);
    }

    private static Object execute(LogicalDag dag, String featureName) {
        OperatorRegistry registry = OperatorRegistry.standard();
        PhysicalPlan plan = new PhysicalPlanner(registry).plan(
                new LogicalDagOptimizer(registry).analyze(dag),
                ExecutionEnvironment.OFFLINE,
                "bigint-widening");
        return new DagRuntime(registry)
                .execute(plan, ExecutionContext.offlineRow("bigint-widening", Map.of()))
                .feature(featureName)
                .raw();
    }
}
