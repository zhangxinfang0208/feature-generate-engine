package com.example.featuredag.operator;

import com.example.featuredag.api.FeatureDagEngine;
import com.example.featuredag.api.GenerateResult;
import com.example.featuredag.api.InitOptions;
import com.example.featuredag.api.OfflineGenerateRequest;
import com.example.featuredag.definition.DataType;
import com.example.featuredag.definition.EntityScope;
import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.physical.ExecutionEnvironment;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class FindIndicesAnyOperatorTest {
    @Test
    public void isRegisteredAsIndependentScalarAdapterOperator() {
        OperatorRegistry registry = OperatorRegistry.standard();

        assertTrue(registry.find("find_indices_any").isPresent());
        assertEquals(2, registry.require("find_indices_any").minArguments());
        assertEquals(2, registry.require("find_indices_any").maxArguments());
        assertEquals(BatchKernelKind.SCALAR_ADAPTER,
                registry.batchKernelKind("find_indices_any"));
    }

    @Test
    public void returnsSourceOrderedIndicesForAnyTargetWithoutDuplicateAmplification() {
        Object result = OperatorRegistry.standard().evaluate(
                "find_indices_any",
                Arrays.<Object>asList(
                        Arrays.asList("c1", "c2", "c3", "c4", "c5", "c6", "c1"),
                        Arrays.asList("c1", "c2", "c3", "c4", "c5", "c1")));

        assertEquals(Arrays.asList(0, 1, 2, 3, 4, 6), result);
        assertThrows(UnsupportedOperationException.class, () -> ((List<?>) result).clear());
    }

    @Test
    public void supportsNullAndEmptyTargets() {
        OperatorRegistry registry = OperatorRegistry.standard();

        assertEquals(
                Arrays.asList(0, 2),
                registry.evaluate(
                        "find_indices_any",
                        Arrays.<Object>asList(
                                Arrays.asList(null, "x", null),
                                Collections.singletonList(null))));
        assertEquals(
                Collections.emptyList(),
                registry.evaluate(
                        "find_indices_any",
                        Arrays.<Object>asList(
                                Arrays.asList("a", "b"),
                                Collections.emptyList())));
    }

    @Test
    public void infersIntegerSequenceAndUnionsInputScopes() {
        OperatorInference inference = OperatorRegistry.standard().infer(
                "find_indices_any",
                Arrays.<OperatorInputMetadata>asList(
                        new TestInput(DataType.STRING, ValueShape.SEQUENCE, EntityScope.USER),
                        new TestInput(DataType.STRING, ValueShape.SEQUENCE, EntityScope.ITEM)));

        assertEquals(DataType.INT, inference.outputType());
        assertEquals(ValueShape.SEQUENCE, inference.valueShape());
        assertEquals(
                Set.of(EntityScope.USER, EntityScope.ITEM),
                inference.entityScopes());
    }

    @Test
    public void rejectsScalarInputDuringInferenceAndRuntime() {
        IllegalArgumentException inferenceFailure = assertThrows(
                IllegalArgumentException.class,
                () -> OperatorRegistry.standard().infer(
                        "find_indices_any",
                        Arrays.<OperatorInputMetadata>asList(
                                new TestInput(
                                        DataType.STRING, ValueShape.SCALAR, EntityScope.USER),
                                new TestInput(
                                        DataType.STRING, ValueShape.SEQUENCE, EntityScope.ITEM))));
        assertTrue(inferenceFailure.getMessage().contains("position 0"));

        IllegalArgumentException runtimeFailure = assertThrows(
                IllegalArgumentException.class,
                () -> OperatorRegistry.standard().evaluate(
                        "find_indices_any",
                        Arrays.<Object>asList(Collections.singletonList("a"), "a")));
        assertTrue(runtimeFailure.getMessage().contains("targets"));
    }

    @Test
    public void scalarBatchAdapterPreservesRowsAndOrder() {
        OperatorRegistry registry = OperatorRegistry.standard();
        BatchOperatorCall call = new BatchOperatorCall(
                new OfflineLayout(2),
                Arrays.<BatchColumn>asList(
                        new ListBatchColumn(Arrays.<Object>asList(
                                Arrays.asList("a", "b", "a"),
                                Arrays.asList("x", "y", "z"))),
                        new ListBatchColumn(Arrays.<Object>asList(
                                Collections.singletonList("a"),
                                Arrays.asList("z", "x")))));

        assertEquals(
                Arrays.asList(Arrays.asList(0, 2), Arrays.asList(0, 2)),
                ((ListBatchColumn) registry.evaluateBatch(
                        "find_indices_any", call).values()).values());
    }

    @Test
    public void executesFilteredCountDescendingExpressionThroughPublicApi() {
        String configJson = "{"
                + "\"feature_set_name\":\"cluster_preference\","
                + "\"version\":\"1.0\","
                + "\"features\":["
                + "{\"name\":\"user_cluster_id_seq\","
                + "\"raw_name\":\"user_cluster_id_seq\","
                + "\"type\":\"STRING\",\"definition_type\":\"BASE\","
                + "\"to_use\":true,\"entity_scopes\":[\"USER\"],"
                + "\"value_shape\":\"SEQUENCE\",\"seq_max_length\":1024},"
                + "{\"name\":\"Item.i2i_top5_cluster_id\","
                + "\"raw_name\":\"Item.i2i_top5_cluster_id\","
                + "\"type\":\"STRING\",\"definition_type\":\"BASE\","
                + "\"to_use\":true,\"entity_scopes\":[\"ITEM\"],"
                + "\"value_shape\":\"SEQUENCE\",\"seq_max_length\":5},"
                + "{\"name\":\"cluster_preference\",\"type\":\"STRING\","
                + "\"definition_type\":\"DERIVED\","
                + "\"expression\":\"group_count_concat("
                + "slice_by_indices(user_cluster_id_seq,"
                + "find_indices_any(user_cluster_id_seq,Item.i2i_top5_cluster_id)),"
                + "{\\\"delimiter\\\":\\\"#\\\","
                + "\\\"order\\\":\\\"COUNT_DESC\\\"})\","
                + "\"output_policy\":\"OUTPUT\",\"to_use\":true,"
                + "\"entity_scopes\":[\"USER\",\"ITEM\"],"
                + "\"value_shape\":\"SEQUENCE\",\"seq_max_length\":5}]}";
        FeatureDagEngine engine = FeatureDagEngine.init(
                configJson,
                InitOptions.builder().environment(ExecutionEnvironment.OFFLINE).build());
        Map<String, List<?>> inputs = new LinkedHashMap<String, List<?>>();
        inputs.put("user_cluster_id_seq",
                Arrays.asList("c1", "c2", "c3", "c2", "c1", "c2", "c4", "c5", "c6"));
        inputs.put("Item.i2i_top5_cluster_id",
                Arrays.asList("c1", "c2", "c3", "c4", "c5"));

        GenerateResult result = engine.generate(new OfflineGenerateRequest("case-1", inputs));

        assertEquals(
                Arrays.asList("c2#3", "c1#2", "c3#1", "c4#1", "c5#1"),
                result.featureValues().get("cluster_preference"));
    }

    private static final class TestInput implements OperatorInputMetadata {
        private final DataType dataType;
        private final ValueShape valueShape;
        private final EntityScope entityScope;

        private TestInput(
                DataType dataType,
                ValueShape valueShape,
                EntityScope entityScope) {
            this.dataType = dataType;
            this.valueShape = valueShape;
            this.entityScope = entityScope;
        }

        @Override public DataType outputType() { return dataType; }
        @Override public Set<EntityScope> entityScopes() {
            return Collections.singleton(entityScope);
        }
        @Override public ValueShape valueShape() { return valueShape; }
        @Override public String sourceFeatureName() { return "test"; }
    }

    private static final class OfflineLayout implements BatchLayout {
        private final int rowCount;

        private OfflineLayout(int rowCount) {
            this.rowCount = rowCount;
        }

        @Override public BatchDomain domain() { return BatchDomain.OFFLINE_ROW; }
        @Override public int rowCount() { return rowCount; }
        @Override public int groupIndexAt(int rowIndex) { return -1; }
        @Override public int indexInGroupAt(int rowIndex) { return rowIndex; }
    }
}
