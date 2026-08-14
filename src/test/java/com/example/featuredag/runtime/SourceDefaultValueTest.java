package com.example.featuredag.runtime;

import com.example.featuredag.config.FeatureConfigLoader;
import com.example.featuredag.config.FeatureConfigMapper;
import com.example.featuredag.config.MappedFeatureSet;
import com.example.featuredag.definition.DataType;
import com.example.featuredag.definition.EntityScope;
import com.example.featuredag.definition.FeatureDefinition;
import com.example.featuredag.definition.FeatureRole;
import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.expression.ExpressionParser;
import com.example.featuredag.logical.LogicalDag;
import com.example.featuredag.logical.LogicalDagBuilder;
import com.example.featuredag.operator.OperatorRegistry;
import com.example.featuredag.physical.ExecutionEnvironment;
import com.example.featuredag.physical.PhysicalPlan;
import com.example.featuredag.physical.PhysicalPlanner;
import com.example.featuredag.planning.LogicalDagOptimizer;

import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

/**
 * 覆盖 null 默认值的统一语义：无论便捷工厂、Builder 还是 JSON dft:null，
 * null 都表示没有可用默认值，源字段缺失时必须在绑定层报错。
 */
public class SourceDefaultValueTest {

    @Test
    public void builderNullDefaultStillThrowsWhenSourceMissing() {
        FeatureDefinition tag = FeatureDefinition.builder()
                .name("tag")
                .role(FeatureRole.RAW)
                .dataType(DataType.STRING)
                .addEntityScope(EntityScope.USER)
                .sourceBinding("tag")
                .defaultValue(null)
                .build();

        assertThrows(IllegalArgumentException.class, () -> execute(tag));
    }

    @Test
    public void sequenceNullDefaultFailsAtSourceBinding() {
        FeatureDefinition numbers = FeatureDefinition.builder()
                .name("numbers")
                .role(FeatureRole.RAW)
                .dataType(DataType.DOUBLE)
                .addEntityScope(EntityScope.USER)
                .sourceBinding("numbers")
                .declaredValueShape(ValueShape.SEQUENCE)
                .defaultValue(null)
                .build();

        assertThrows(IllegalArgumentException.class, () -> execute(numbers));
    }

    @Test
    public void noDefaultConfiguredStillThrowsWhenSourceMissing() {
        FeatureDefinition tag = FeatureDefinition.raw("tag", DataType.STRING, EntityScope.USER, null);

        assertThrows(IllegalArgumentException.class, () -> execute(tag));
    }

    @Test
    public void nonNullDefaultStillBindsWhenSourceMissing() {
        FeatureDefinition tag = FeatureDefinition.raw(
                "tag", DataType.STRING, EntityScope.USER, "fallback");

        assertEquals("fallback", execute(tag).feature("tag").raw());
    }

    @Test
    public void configTreatsExplicitNullDefaultAsMissingField() {
        FeatureDefinition explicitNull = mappedBaseDefinition("\"dft\": null,");
        assertThrows(IllegalArgumentException.class, () -> execute(explicitNull));

        FeatureDefinition missing = mappedBaseDefinition("");
        assertThrows(IllegalArgumentException.class, () -> execute(missing));
    }

    private static ExecutionResult execute(FeatureDefinition tag) {
        OperatorRegistry registry = OperatorRegistry.standard();
        LogicalDag dag = new LogicalDagBuilder(new ExpressionParser(), registry)
                .build(List.of(tag), Set.of(tag.name()));
        PhysicalPlan plan = new PhysicalPlanner(registry).plan(
                new LogicalDagOptimizer(registry).analyze(dag),
                ExecutionEnvironment.OFFLINE,
                "default-value-case");
        // 空共享源值：源特征在本次执行中缺失，触发默认值/报错分支。
        return new DagRuntime(registry).execute(
                plan, ExecutionContext.offlineRow("default-value-case", Map.of()));
    }

    private static FeatureDefinition mappedBaseDefinition(String defaultField) {
        String json = """
                {
                  "feature_set_name": "default-value-config",
                  "version": "1",
                  "features": [
                    {
                      "name": "tag",
                      "raw_name": "tag",
                      "type": "STRING",
                      "definition_type": "BASE",
                      %s
                      "entity_scopes": ["USER"]
                    },
                    {
                      "name": "tag_output",
                      "type": "STRING",
                      "definition_type": "DERIVED",
                      "expression": "tag",
                      "output_policy": "OUTPUT"
                    }
                  ]
                }
                """.formatted(defaultField);
        MappedFeatureSet mapped = FeatureConfigMapper.map(
                FeatureConfigLoader.load(json), Set.of(), Map.of());
        return mapped.definitions().stream()
                .filter(definition -> definition.name().equals("tag"))
                .findFirst()
                .orElseThrow();
    }
}
