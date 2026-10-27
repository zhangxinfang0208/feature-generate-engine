package com.example.featuredag.operator;

import com.example.featuredag.api.FeatureDagEngine;
import com.example.featuredag.api.InitOptions;
import com.example.featuredag.api.OfflineGenerateRequest;
import com.example.featuredag.definition.DataType;
import com.example.featuredag.definition.EntityScope;
import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.operator.builtin.InitialBusinessOperators;
import com.example.featuredag.operator.builtin.LowerOperator;
import com.example.featuredag.physical.ExecutionEnvironment;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

public final class LowerOperatorTest {
    private final OperatorRegistry registry = OperatorRegistry.standard();

    @Test
    public void registersPureIndependentOperatorAndInfersShapes() {
        assertEquals(26, InitialBusinessOperators.definitions().size());
        OperatorDefinition operator = registry.require("lower");
        assertTrue(operator instanceof LowerOperator);
        assertEquals(1, operator.minArguments());
        assertEquals(1, operator.maxArguments());
        assertTrue(operator.deterministic());
        assertTrue(operator.sideEffectFree());
        assertTrue(operator.supportsSequenceView());
        assertEquals(BatchKernelKind.SCALAR_ADAPTER, registry.batchKernelKind("lower"));
        for (ValueShape shape : List.of(ValueShape.SCALAR, ValueShape.SEQUENCE,
                ValueShape.CANDIDATE_VECTOR)) {
            OperatorInference result = registry.infer("lower", List.of(
                    new TestInput(DataType.STRING, Set.of(EntityScope.ITEM), shape)));
            assertEquals(DataType.STRING, result.outputType());
            assertEquals(shape == ValueShape.SEQUENCE ? shape : ValueShape.SCALAR, result.valueShape());
            assertEquals(Set.of(EntityScope.ITEM), result.entityScopes());
        }
    }

    @Test
    public void lowercasesStringsWithoutChangingOtherCharactersOrDefaultLocale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals("com.wa.yy", lower("COM.WA.YY"));
            assertEquals("i", lower("I"));
            assertEquals("äé中文 123._-", lower("ÄÉ中文 123._-"));
            assertEquals(" com.wa.yy ", lower(" COM.WA.YY "));
            assertEquals("", lower(""));
            assertEquals("com.wa.yy", lower("com.wa.yy"));
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    public void mapsListsAndSequenceViewsWithoutMutatingInput() {
        List<String> input = Arrays.asList("COM.WA.YY", "Mixed", "COM.WA.YY", "");
        List<String> expected = List.of("com.wa.yy", "mixed", "com.wa.yy", "");
        Object output = lower(input);
        assertEquals(expected, output);
        assertEquals("COM.WA.YY", input.get(0));
        assertThrows(UnsupportedOperationException.class, () -> ((List<?>) output).clear());
        assertEquals(List.of(), lower(List.of()));
        OperatorSequence view = new OperatorSequence() {
            @Override public int size() { return input.size(); }
            @Override public Object elementAt(int index) { return input.get(index); }
            @Override public OperatorSequence filterByColumn(String column, Object value) {
                throw new UnsupportedOperationException();
            }
        };
        assertEquals(expected, lower(view));
    }

    @Test
    public void rejectsInvalidArityTypesAndShapes() {
        assertThrows(IllegalArgumentException.class, () -> registry.evaluate("lower", List.of()));
        assertThrows(IllegalArgumentException.class, () -> registry.evaluate("lower", List.of("A", "B")));
        for (Object value : Arrays.asList(null, 123, true, Map.of("x", "A"), List.of("A", 1),
                Arrays.asList("A", null), List.of(List.of("A")))) {
            assertThrows(IllegalArgumentException.class, () -> lower(value));
        }
        for (DataType type : DataType.values()) {
            if (type == DataType.STRING) continue;
            assertThrows(IllegalArgumentException.class, () -> registry.infer("lower", List.of(
                    new TestInput(type, Set.of(EntityScope.USER), ValueShape.SCALAR))));
        }
        for (ValueShape shape : List.of(ValueShape.OBJECT, ValueShape.INDEX)) {
            assertThrows(IllegalArgumentException.class, () -> registry.infer("lower", List.of(
                    new TestInput(DataType.STRING, Set.of(EntityScope.USER), shape))));
        }
    }

    @Test
    public void batchAdapterMatchesSingleAndReportsFailureRow() {
        for (BatchDomain domain : BatchDomain.values()) {
            List<Object> inputs = List.of("COM.WA.YY", List.of("A", "B"), "");
            BatchOperatorResult result = registry.evaluateBatch("lower", new BatchOperatorCall(
                    new FixedBatchLayout(domain, inputs.size()), List.of(new ListBatchColumn(inputs))));
            for (int row = 0; row < inputs.size(); row++) {
                assertEquals(lower(inputs.get(row)), result.values().valueAt(row));
            }
        }
        BatchOperatorEvaluationException failure = assertThrows(BatchOperatorEvaluationException.class,
                () -> registry.evaluateBatch("lower", new BatchOperatorCall(
                        new FixedBatchLayout(BatchDomain.OFFLINE_ROW, 2),
                        List.of(new ListBatchColumn(List.of("OK", 123))))));
        assertEquals(1, failure.rowIndex());
    }

    @Test
    public void executesScalarSequenceAndNestedExpressionsThroughPublicApi() {
        String config = """
                {"feature_set_name":"lower-test","version":"1","features":[
                  {"name":"package_name","raw_name":"package_name","type":"STRING",
                   "definition_type":"BASE","entity_scopes":["USER"],"value_shape":"SCALAR"},
                  {"name":"packages","raw_name":"packages","type":"STRING",
                   "definition_type":"BASE","entity_scopes":["USER"],"value_shape":"SEQUENCE"},
                  {"name":"normalized","type":"STRING","definition_type":"DERIVED",
                   "expression":"lower(package_name)","output_policy":"OUTPUT",
                   "entity_scopes":["USER"],"value_shape":"SCALAR"},
                  {"name":"normalized_seq","type":"STRING","definition_type":"DERIVED",
                   "expression":"lower(packages)","output_policy":"OUTPUT",
                   "entity_scopes":["USER"],"value_shape":"SEQUENCE"},
                  {"name":"nested","type":"STRING","definition_type":"DERIVED",
                   "expression":"lower(first_element(packages))","output_policy":"OUTPUT",
                   "entity_scopes":["USER"],"value_shape":"SCALAR"}]}
                """;
        FeatureDagEngine engine = FeatureDagEngine.init(config, InitOptions.builder()
                .environment(ExecutionEnvironment.OFFLINE).build());
        Map<String, List<?>> output = engine.generate(new OfflineGenerateRequest("row", Map.of(
                "package_name", List.of("COM.WA.YY"), "packages", List.of("COM.WA.YY", "ABC"))))
                .featureValues();
        assertEquals(List.of("com.wa.yy"), output.get("normalized"));
        assertEquals(List.of("com.wa.yy", "abc"), output.get("normalized_seq"));
        assertEquals(List.of("com.wa.yy"), output.get("nested"));
    }

    private Object lower(Object value) {
        return registry.evaluate("lower", Collections.singletonList(value));
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
