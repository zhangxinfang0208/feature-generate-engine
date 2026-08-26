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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** concat 标准算子的注册、推断、执行、异常、Batch 路由和公共 API 集成测试。 */
public final class ConcatOperatorTest {
    @Test
    public void metadataAndInferenceRemainScalar() {
        OperatorRegistry registry = registry();
        OperatorDefinition definition = registry.require("concat");
        assertEquals(2, definition.minArguments());
        assertEquals(Integer.MAX_VALUE, definition.maxArguments());
        assertTrue(definition.deterministic());
        assertTrue(definition.sideEffectFree());
        assertFalse(definition.supportsSequenceView());
        assertEquals(BatchKernelKind.SCALAR_ADAPTER, registry.batchKernelKind("concat"));

        TestInput userString = new TestInput(
                DataType.STRING, Collections.singleton(EntityScope.USER), ValueShape.SCALAR);
        TestInput itemInt = new TestInput(
                DataType.INT, Collections.singleton(EntityScope.ITEM), ValueShape.SCALAR);
        TestInput config = new TestInput(
                DataType.OBJECT, Collections.<EntityScope>emptySet(), ValueShape.OBJECT);
        OperatorInference inference = registry.infer(
                "concat", Arrays.<OperatorInputMetadata>asList(userString, itemInt, config));

        assertEquals(DataType.STRING, inference.outputType());
        assertEquals(ValueShape.SCALAR, inference.valueShape());
        assertEquals(
                Set.of(EntityScope.USER, EntityScope.ITEM),
                inference.entityScopes());
        assertTrue(OperatorRegistry.standard().find("concat").isPresent());
    }

    @Test
    public void concatenatesScalarValuesWithDefaultAndConfiguredDelimiter() {
        OperatorRegistry registry = registry();
        assertEquals("left#7", registry.evaluate("concat", Arrays.<Object>asList("left", 7)));
        assertEquals(
                "a#2#true",
                registry.evaluate("concat", Arrays.<Object>asList("a", 2, true)));

        Map<String, Object> config = Collections.<String, Object>singletonMap("delimiter", "|");
        assertEquals(
                "left|right",
                registry.evaluate("concat", Arrays.<Object>asList("left", "right", config)));
        assertEquals(
                "left#null",
                registry.evaluate("concat", Arrays.<Object>asList("left", null)));
    }

    @Test
    public void rejectsNonScalarInputsAndInsufficientValues() {
        OperatorRegistry registry = registry();
        TestInput scalar = new TestInput(
                DataType.STRING, Collections.singleton(EntityScope.USER), ValueShape.SCALAR);
        TestInput sequence = new TestInput(
                DataType.STRING, Collections.singleton(EntityScope.USER), ValueShape.SEQUENCE);
        TestInput config = new TestInput(
                DataType.OBJECT, Collections.<EntityScope>emptySet(), ValueShape.OBJECT);

        IllegalArgumentException sequenceInference = assertThrows(
                IllegalArgumentException.class,
                () -> registry.infer(
                        "concat", Arrays.<OperatorInputMetadata>asList(scalar, sequence)));
        assertTrue(sequenceInference.getMessage().contains("expects scalar"));

        IllegalArgumentException onlyOneValue = assertThrows(
                IllegalArgumentException.class,
                () -> registry.infer(
                        "concat", Arrays.<OperatorInputMetadata>asList(scalar, config)));
        assertTrue(onlyOneValue.getMessage().contains("at least two scalar"));

        IllegalArgumentException sequenceEvaluation = assertThrows(
                IllegalArgumentException.class,
                () -> registry.evaluate(
                        "concat", Arrays.<Object>asList("left", Arrays.asList("a", "b"))));
        assertTrue(sequenceEvaluation.getMessage().contains("got sequence"));

        Map<String, Object> objectValue = Collections.<String, Object>singletonMap("id", "a");
        IllegalArgumentException objectEvaluation = assertThrows(
                IllegalArgumentException.class,
                () -> registry.evaluate(
                        "concat", Arrays.<Object>asList(objectValue, "right", "tail")));
        assertTrue(objectEvaluation.getMessage().contains("object value"));
    }

    @Test
    public void scalarBatchAdapterPreservesRowsAndFailureIndex() {
        OperatorRegistry registry = registry();
        Map<String, Object> config = Collections.<String, Object>singletonMap("delimiter", "-");
        BatchOperatorCall call = new BatchOperatorCall(
                new FixedBatchLayout(BatchDomain.OFFLINE_ROW, 3),
                Arrays.<BatchColumn>asList(
                        new ListBatchColumn(Arrays.<Object>asList("a", "b", "c")),
                        new ListBatchColumn(Arrays.<Object>asList(1, 2, 3)),
                        new ListBatchColumn(Arrays.<Object>asList(config, config, config))));

        BatchOperatorResult result = registry.evaluateBatch("concat", call);
        assertEquals("a-1", result.values().valueAt(0));
        assertEquals("b-2", result.values().valueAt(1));
        assertEquals("c-3", result.values().valueAt(2));

        BatchOperatorCall invalid = new BatchOperatorCall(
                new FixedBatchLayout(BatchDomain.OFFLINE_ROW, 2),
                Arrays.<BatchColumn>asList(
                        new ListBatchColumn(Arrays.<Object>asList("a", "b")),
                        new ListBatchColumn(Arrays.<Object>asList("x", Arrays.asList("y")))));
        BatchOperatorEvaluationException failure = assertThrows(
                BatchOperatorEvaluationException.class,
                () -> registry.evaluateBatch("concat", invalid));
        assertEquals(1, failure.rowIndex());
    }

    @Test
    public void executesThroughFeatureDagEngineStandardRegistry() {
        String configJson = "{"
                + "\"feature_set_name\":\"person-label\","
                + "\"version\":\"1\","
                + "\"features\":["
                + "{\"name\":\"first_name\",\"raw_name\":\"first_name\","
                + "\"type\":\"STRING\",\"definition_type\":\"BASE\","
                + "\"entity_scopes\":[\"USER\"],\"value_shape\":\"SCALAR\"},"
                + "{\"name\":\"last_name\",\"raw_name\":\"last_name\","
                + "\"type\":\"STRING\",\"definition_type\":\"BASE\","
                + "\"entity_scopes\":[\"USER\"],\"value_shape\":\"SCALAR\"},"
                + "{\"name\":\"full_name\",\"type\":\"STRING\","
                + "\"definition_type\":\"DERIVED\","
                + "\"expression\":\"concat(first_name, last_name, "
                + "{\\\"delimiter\\\":\\\" \\\"})\","
                + "\"output_policy\":\"OUTPUT\",\"entity_scopes\":[\"USER\"],"
                + "\"value_shape\":\"SCALAR\"}]}";
        InitOptions options = InitOptions.builder()
                .environment(ExecutionEnvironment.OFFLINE)
                .build();
        FeatureDagEngine engine = FeatureDagEngine.init(configJson, options);

        Map<String, List<?>> inputs = new LinkedHashMap<String, List<?>>();
        inputs.put("first_name", Collections.singletonList("Ada"));
        inputs.put("last_name", Collections.singletonList("Lovelace"));
        GenerateResult result = engine.generate(
                new OfflineGenerateRequest("person-label-row", inputs));

        assertEquals(Collections.singletonList("Ada Lovelace"),
                result.featureValues().get("full_name"));
    }

    private static OperatorRegistry registry() {
        return OperatorRegistry.standard();
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

        @Override
        public DataType outputType() {
            return outputType;
        }

        @Override
        public Set<EntityScope> entityScopes() {
            return entityScopes;
        }

        @Override
        public ValueShape valueShape() {
            return valueShape;
        }

        @Override
        public String sourceFeatureName() {
            return null;
        }
    }

    private static final class FixedBatchLayout implements BatchLayout {
        private final BatchDomain domain;
        private final int rowCount;

        private FixedBatchLayout(BatchDomain domain, int rowCount) {
            this.domain = domain;
            this.rowCount = rowCount;
        }

        @Override
        public BatchDomain domain() {
            return domain;
        }

        @Override
        public int rowCount() {
            return rowCount;
        }

        @Override
        public int groupIndexAt(int rowIndex) {
            return -1;
        }

        @Override
        public int indexInGroupAt(int rowIndex) {
            return rowIndex;
        }
    }
}
