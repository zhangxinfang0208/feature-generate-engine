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

/** append 标准算子的注册、推断、执行、异常、Batch 路由和公共 API 集成测试。 */
public final class AppendOperatorTest {
    @Test
    public void standardRegistryExposesAppendMetadata() {
        OperatorRegistry registry = OperatorRegistry.standard();
        OperatorDefinition definition = registry.require("append");

        assertEquals(2, definition.minArguments());
        assertEquals(2, definition.maxArguments());
        assertTrue(definition.deterministic());
        assertTrue(definition.sideEffectFree());
        assertTrue(definition.supportsSequenceView());
        assertEquals(BatchKernelKind.SCALAR_ADAPTER, registry.batchKernelKind("append"));
    }

    @Test
    public void inferenceAlwaysReturnsSequenceAndSafelyWidensNumericElements() {
        OperatorRegistry registry = OperatorRegistry.standard();
        TestInput intSequence = new TestInput(
                DataType.INT, Collections.singleton(EntityScope.USER), ValueShape.SEQUENCE);
        TestInput bigintScalar = new TestInput(
                DataType.BIGINT, Collections.singleton(EntityScope.ITEM), ValueShape.SCALAR);
        OperatorInference bigint = registry.infer(
                "append", Arrays.<OperatorInputMetadata>asList(intSequence, bigintScalar));

        assertEquals(DataType.BIGINT, bigint.outputType());
        assertEquals(ValueShape.SEQUENCE, bigint.valueShape());
        assertEquals(Set.of(EntityScope.USER, EntityScope.ITEM), bigint.entityScopes());

        TestInput doubleSequence = new TestInput(
                DataType.DOUBLE, Collections.singleton(EntityScope.USER), ValueShape.SEQUENCE);
        OperatorInference doubleResult = registry.infer(
                "append", Arrays.<OperatorInputMetadata>asList(bigintScalar, doubleSequence));
        assertEquals(DataType.DOUBLE, doubleResult.outputType());
        assertEquals(ValueShape.SEQUENCE, doubleResult.valueShape());
    }

    @Test
    public void evaluatesAllScalarAndSequenceCombinationsInArgumentOrder() {
        OperatorRegistry registry = OperatorRegistry.standard();

        assertEquals(
                Arrays.asList("a", "b", "c", "d"),
                registry.evaluate("append", Arrays.<Object>asList(
                        Arrays.asList("a", "b"), Arrays.asList("c", "d"))));
        assertEquals(
                Arrays.asList("a", "b", "c"),
                registry.evaluate("append", Arrays.<Object>asList(
                        Arrays.asList("a", "b"), "c")));
        assertEquals(
                Arrays.asList("a", "b", "c"),
                registry.evaluate("append", Arrays.<Object>asList(
                        "a", Arrays.asList("b", "c"))));
        assertEquals(
                Arrays.asList("a", "b"),
                registry.evaluate("append", Arrays.<Object>asList("a", "b")));
        assertEquals(
                Collections.singletonList("tail"),
                registry.evaluate("append", Arrays.<Object>asList(
                        Collections.emptyList(), "tail")));
    }

    @Test
    public void safelyWidensRuntimeNumericElementsToOneCarrierType() {
        OperatorRegistry registry = OperatorRegistry.standard();

        assertEquals(
                Arrays.asList(1L, 2L, 3L),
                registry.evaluate(
                        "append",
                        Arrays.<Object>asList(Arrays.asList(1, 2), 3L)));
        assertEquals(
                Arrays.asList(1.0, 2.5),
                registry.evaluate(
                        "append",
                        Arrays.<Object>asList(1, Collections.singletonList(2.5))));
    }

    @Test
    public void rejectsUnsupportedTypesAndShapesDuringInference() {
        OperatorRegistry registry = OperatorRegistry.standard();
        TestInput object = new TestInput(
                DataType.OBJECT, Collections.singleton(EntityScope.USER), ValueShape.OBJECT);
        IllegalArgumentException objectFailure = assertThrows(
                IllegalArgumentException.class,
                () -> registry.infer(
                        "append", Arrays.<OperatorInputMetadata>asList(object, object)));
        assertTrue(objectFailure.getMessage().contains("OBJECT"));

        TestInput eventSequence = new TestInput(
                DataType.EVENT_SEQUENCE,
                Collections.singleton(EntityScope.USER),
                ValueShape.SEQUENCE);
        IllegalArgumentException eventFailure = assertThrows(
                IllegalArgumentException.class,
                () -> registry.infer(
                        "append",
                        Arrays.<OperatorInputMetadata>asList(eventSequence, eventSequence)));
        assertTrue(eventFailure.getMessage().contains("EVENT_SEQUENCE"));

        TestInput stringSequence = new TestInput(
                DataType.STRING,
                Collections.singleton(EntityScope.USER),
                ValueShape.SEQUENCE);
        TestInput intScalar = new TestInput(
                DataType.INT,
                Collections.singleton(EntityScope.USER),
                ValueShape.SCALAR);
        IllegalArgumentException incompatible = assertThrows(
                IllegalArgumentException.class,
                () -> registry.infer(
                        "append",
                        Arrays.<OperatorInputMetadata>asList(stringSequence, intScalar)));
        assertTrue(incompatible.getMessage().contains("compatible element types"));
    }

    @Test
    public void rejectsStructuredValuesAtRuntime() {
        OperatorRegistry registry = OperatorRegistry.standard();
        IllegalArgumentException scalarObject = assertThrows(
                IllegalArgumentException.class,
                () -> registry.evaluate(
                        "append",
                        Arrays.<Object>asList(
                                Collections.<String, Object>singletonMap("id", "a"),
                                "tail")));
        assertTrue(scalarObject.getMessage().contains("object"));

        IllegalArgumentException eventElement = assertThrows(
                IllegalArgumentException.class,
                () -> registry.evaluate(
                        "append",
                        Arrays.<Object>asList(
                                Collections.singletonList(
                                        Collections.<String, Object>singletonMap("id", "a")),
                                Collections.emptyList())));
        assertTrue(eventElement.getMessage().contains("object"));
        assertTrue(eventElement.getMessage().contains("index 0"));

        IllegalArgumentException incompatibleElements = assertThrows(
                IllegalArgumentException.class,
                () -> registry.evaluate(
                        "append",
                        Arrays.<Object>asList(Collections.singletonList("a"), 1)));
        assertTrue(incompatibleElements.getMessage().contains("compatible element types"));
    }

    @Test
    public void scalarBatchAdapterPreservesRowsAndCombinationModes() {
        OperatorRegistry registry = OperatorRegistry.standard();
        BatchOperatorCall call = new BatchOperatorCall(
                new OfflineLayout(3),
                Arrays.<BatchColumn>asList(
                        new ListBatchColumn(Arrays.<Object>asList(
                                Arrays.asList("a", "b"),
                                "x",
                                "p")),
                        new ListBatchColumn(Arrays.<Object>asList(
                                "c",
                                Arrays.asList("y", "z"),
                                "q"))));

        assertEquals(
                Arrays.asList(
                        Arrays.asList("a", "b", "c"),
                        Arrays.asList("x", "y", "z"),
                        Arrays.asList("p", "q")),
                ((ListBatchColumn) registry.evaluateBatch("append", call).values()).values());
    }

    @Test
    public void executesSequenceAppendThroughPublicApi() {
        String configJson = "{"
                + "\"feature_set_name\":\"append-feature\","
                + "\"version\":\"1\","
                + "\"features\":["
                + "{\"name\":\"left_values\",\"raw_name\":\"left_values\","
                + "\"type\":\"STRING\",\"definition_type\":\"BASE\","
                + "\"entity_scopes\":[\"USER\"],\"value_shape\":\"SEQUENCE\"},"
                + "{\"name\":\"right_values\",\"raw_name\":\"right_values\","
                + "\"type\":\"STRING\",\"definition_type\":\"BASE\","
                + "\"entity_scopes\":[\"USER\"],\"value_shape\":\"SEQUENCE\"},"
                + "{\"name\":\"combined_values\",\"type\":\"STRING\","
                + "\"definition_type\":\"DERIVED\","
                + "\"expression\":\"append(left_values, right_values)\","
                + "\"output_policy\":\"OUTPUT\",\"entity_scopes\":[\"USER\"],"
                + "\"value_shape\":\"SEQUENCE\"}]}";
        FeatureDagEngine engine = FeatureDagEngine.init(
                configJson,
                InitOptions.builder().environment(ExecutionEnvironment.OFFLINE).build());
        Map<String, List<?>> inputs = new LinkedHashMap<String, List<?>>();
        inputs.put("left_values", Arrays.asList("a", "b"));
        inputs.put("right_values", Collections.singletonList("c"));

        GenerateResult result = engine.generate(
                new OfflineGenerateRequest("append-row", inputs));

        assertEquals(
                Arrays.asList("a", "b", "c"),
                result.featureValues().get("combined_values"));
    }

    @Test
    public void consumesOperatorSequenceWithoutMaterializationContractChanges() {
        Object result = OperatorRegistry.standard().evaluate(
                "append",
                Arrays.<Object>asList(
                        new TestSequence(Arrays.<Object>asList("a", "b")),
                        "c"));

        assertEquals(Arrays.asList("a", "b", "c"), result);
        assertThrows(UnsupportedOperationException.class, () -> ((List<?>) result).clear());
    }

    private static final class TestInput implements OperatorInputMetadata {
        private final DataType outputType;
        private final Set<EntityScope> entityScopes;
        private final ValueShape valueShape;

        private TestInput(
                DataType outputType,
                Set<EntityScope> entityScopes,
                ValueShape valueShape) {
            this.outputType = outputType;
            this.entityScopes = entityScopes;
            this.valueShape = valueShape;
        }

        @Override public DataType outputType() { return outputType; }
        @Override public Set<EntityScope> entityScopes() { return entityScopes; }
        @Override public ValueShape valueShape() { return valueShape; }
        @Override public String sourceFeatureName() { return null; }
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

    private static final class TestSequence implements OperatorSequence {
        private final List<Object> values;

        private TestSequence(List<Object> values) {
            this.values = values;
        }

        @Override public int size() { return values.size(); }
        @Override public Object elementAt(int index) { return values.get(index); }
        @Override public OperatorSequence filterByColumn(String column, Object value) {
            throw new UnsupportedOperationException("not needed by append test");
        }
    }
}
