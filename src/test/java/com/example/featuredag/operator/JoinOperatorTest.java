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

/** join 标准算子的注册、推断、执行、异常、Batch 路由和公共 API 集成测试。 */
public final class JoinOperatorTest {
    @Test
    public void standardRegistryExposesJoinMetadata() {
        OperatorRegistry registry = OperatorRegistry.standard();
        OperatorDefinition definition = registry.require("join");

        assertEquals(1, definition.minArguments());
        assertEquals(2, definition.maxArguments());
        assertTrue(definition.deterministic());
        assertTrue(definition.sideEffectFree());
        assertTrue(definition.supportsSequenceView());
        assertEquals(BatchKernelKind.SCALAR_ADAPTER, registry.batchKernelKind("join"));
    }

    @Test
    public void inferenceReturnsStringScalarAndUnionsInputScopes() {
        OperatorRegistry registry = OperatorRegistry.standard();
        TestInput sequence = new TestInput(
                DataType.STRING,
                Collections.singleton(EntityScope.USER),
                ValueShape.SEQUENCE);
        TestInput delimiter = new TestInput(
                DataType.STRING,
                Collections.singleton(EntityScope.ITEM),
                ValueShape.SCALAR);

        OperatorInference inference = registry.infer(
                "join", Arrays.<OperatorInputMetadata>asList(sequence, delimiter));

        assertEquals(DataType.STRING, inference.outputType());
        assertEquals(ValueShape.SCALAR, inference.valueShape());
        assertEquals(Set.of(EntityScope.USER, EntityScope.ITEM), inference.entityScopes());
    }

    @Test
    public void joinsSequenceWithDefaultOrExplicitDelimiterAndHandlesEmptySequence() {
        OperatorRegistry registry = OperatorRegistry.standard();

        assertEquals(
                "a#b",
                registry.evaluate(
                        "join", Collections.<Object>singletonList(Arrays.asList("a", "b"))));
        assertEquals(
                "1|true|null",
                registry.evaluate(
                        "join",
                        Arrays.<Object>asList(Arrays.asList(1, true, null), "|")));
        assertEquals(
                "",
                registry.evaluate(
                        "join",
                        Arrays.<Object>asList(Collections.emptyList(), "/")));
        assertEquals(
                "only",
                registry.evaluate(
                        "join", Collections.<Object>singletonList(
                                Collections.singletonList("only"))));
    }

    @Test
    public void rejectsInvalidSequenceAndDelimiterMetadata() {
        OperatorRegistry registry = OperatorRegistry.standard();
        TestInput stringScalar = new TestInput(
                DataType.STRING,
                Collections.singleton(EntityScope.USER),
                ValueShape.SCALAR);
        IllegalArgumentException scalarSequence = assertThrows(
                IllegalArgumentException.class,
                () -> registry.infer("join", Collections.singletonList(stringScalar)));
        assertTrue(scalarSequence.getMessage().contains("sequence"));

        TestInput eventSequence = new TestInput(
                DataType.EVENT_SEQUENCE,
                Collections.singleton(EntityScope.USER),
                ValueShape.SEQUENCE);
        IllegalArgumentException eventFailure = assertThrows(
                IllegalArgumentException.class,
                () -> registry.infer("join", Collections.singletonList(eventSequence)));
        assertTrue(eventFailure.getMessage().contains("event"));

        TestInput stringSequence = new TestInput(
                DataType.STRING,
                Collections.singleton(EntityScope.USER),
                ValueShape.SEQUENCE);
        TestInput intDelimiter = new TestInput(
                DataType.INT,
                Collections.<EntityScope>emptySet(),
                ValueShape.SCALAR);
        IllegalArgumentException delimiterFailure = assertThrows(
                IllegalArgumentException.class,
                () -> registry.infer(
                        "join",
                        Arrays.<OperatorInputMetadata>asList(stringSequence, intDelimiter)));
        assertTrue(delimiterFailure.getMessage().contains("string scalar delimiter"));
    }

    @Test
    public void rejectsInvalidRuntimeSequenceDelimiterAndElements() {
        OperatorRegistry registry = OperatorRegistry.standard();
        IllegalArgumentException scalarSequence = assertThrows(
                IllegalArgumentException.class,
                () -> registry.evaluate(
                        "join", Collections.<Object>singletonList("not-a-sequence")));
        assertTrue(scalarSequence.getMessage().contains("sequence"));

        IllegalArgumentException numericDelimiter = assertThrows(
                IllegalArgumentException.class,
                () -> registry.evaluate(
                        "join",
                        Arrays.<Object>asList(Collections.singletonList("a"), 7)));
        assertTrue(numericDelimiter.getMessage().contains("string delimiter"));

        IllegalArgumentException objectElement = assertThrows(
                IllegalArgumentException.class,
                () -> registry.evaluate(
                        "join",
                        Collections.<Object>singletonList(
                                Collections.singletonList(
                                        Collections.<String, Object>singletonMap("id", "a")))));
        assertTrue(objectElement.getMessage().contains("object"));
        assertTrue(objectElement.getMessage().contains("index 0"));
    }

    @Test
    public void scalarBatchAdapterPreservesRowsAndDelimiters() {
        OperatorRegistry registry = OperatorRegistry.standard();
        BatchOperatorCall call = new BatchOperatorCall(
                new OfflineLayout(3),
                Arrays.<BatchColumn>asList(
                        new ListBatchColumn(Arrays.<Object>asList(
                                Arrays.asList("a", "b"),
                                Arrays.asList(1, 2),
                                Arrays.asList("x", "y"))),
                        new ListBatchColumn(Arrays.<Object>asList("#", "|", ""))));

        assertEquals(
                Arrays.asList("a#b", "1|2", "xy"),
                ((ListBatchColumn) registry.evaluateBatch("join", call).values()).values());
    }

    @Test
    public void executesJoinThroughPublicApi() {
        String configJson = "{"
                + "\"feature_set_name\":\"join-feature\","
                + "\"version\":\"1\","
                + "\"features\":["
                + "{\"name\":\"tags\",\"raw_name\":\"tags\","
                + "\"type\":\"STRING\",\"definition_type\":\"BASE\","
                + "\"entity_scopes\":[\"USER\"],\"value_shape\":\"SEQUENCE\"},"
                + "{\"name\":\"joined_tags\",\"type\":\"STRING\","
                + "\"definition_type\":\"DERIVED\","
                + "\"expression\":\"join(tags, '|')\","
                + "\"output_policy\":\"OUTPUT\",\"entity_scopes\":[\"USER\"],"
                + "\"value_shape\":\"SCALAR\"}]}";
        FeatureDagEngine engine = FeatureDagEngine.init(
                configJson,
                InitOptions.builder().environment(ExecutionEnvironment.OFFLINE).build());
        Map<String, List<?>> inputs = new LinkedHashMap<String, List<?>>();
        inputs.put("tags", Arrays.asList("sports", "music", "travel"));

        GenerateResult result = engine.generate(
                new OfflineGenerateRequest("join-row", inputs));

        assertEquals(
                Collections.singletonList("sports|music|travel"),
                result.featureValues().get("joined_tags"));
    }

    @Test
    public void consumesOperatorSequenceDirectly() {
        Object result = OperatorRegistry.standard().evaluate(
                "join",
                Arrays.<Object>asList(
                        new TestSequence(Arrays.<Object>asList("a", "b", "c")),
                        "-"));

        assertEquals("a-b-c", result);
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
            throw new UnsupportedOperationException("not needed by join test");
        }
    }
}
