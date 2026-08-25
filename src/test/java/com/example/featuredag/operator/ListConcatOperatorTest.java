package com.example.featuredag.operator;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.definition.EntityScope;
import com.example.featuredag.definition.ValueShape;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class ListConcatOperatorTest {
    @Test
    public void broadcastsSecondSequenceFirstElement() {
        OperatorRegistry registry = OperatorRegistry.standard();
        assertTrue("list_concat must be registered", registry.find("list_concat").isPresent());

        Object result = registry.evaluate(
                "list_concat",
                Arrays.<Object>asList(
                        Arrays.asList("a", "b", "a"),
                        Arrays.asList("电商")));

        assertEquals(Arrays.asList("a#电商", "b#电商", "a#电商"), result);
    }

    @Test
    public void objectConfigOverridesDelimiter() {
        OperatorRegistry registry = OperatorRegistry.standard();
        assertEquals(3, registry.require("list_concat").maxArguments());

        Object result = registry.evaluate(
                "list_concat",
                Arrays.<Object>asList(
                        Arrays.asList("James", "Kobe", "Jordan"),
                        Collections.singletonList("NBA"),
                        Collections.<String, Object>singletonMap("delimiter", "|")));

        assertEquals(
                Arrays.asList("James|NBA", "Kobe|NBA", "Jordan|NBA"),
                result);
    }

    @Test
    public void scalarBatchAdapterPreservesRowsAndOrder() {
        OperatorRegistry registry = OperatorRegistry.standard();
        BatchOperatorCall call = new BatchOperatorCall(
                new OfflineLayout(2),
                Arrays.<BatchColumn>asList(
                        new ListBatchColumn(Arrays.<Object>asList(
                                Arrays.asList("a", "b"),
                                Arrays.asList("x", "y"))),
                        new ListBatchColumn(Arrays.<Object>asList(
                                Collections.singletonList("电商"),
                                Collections.singletonList("NBA")))));

        assertEquals(BatchKernelKind.SCALAR_ADAPTER,
                registry.batchKernelKind("list_concat"));
        assertEquals(
                Arrays.asList(
                        Arrays.asList("a#电商", "b#电商"),
                        Arrays.asList("x#NBA", "y#NBA")),
                ((ListBatchColumn) registry.evaluateBatch(
                        "list_concat", call).values()).values());
    }

    @Test
    public void rejectsEmptySuffixSequenceWithOperatorContext() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OperatorRegistry.standard().evaluate(
                        "list_concat",
                        Arrays.<Object>asList(
                                Collections.singletonList("a"),
                                Collections.emptyList())));

        assertTrue(failure.getMessage().contains("list_concat"));
        assertTrue(failure.getMessage().contains("must not be empty"));
    }

    @Test
    public void rejectsScalarInputDuringInference() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OperatorRegistry.standard().infer(
                        "list_concat",
                        Arrays.asList(
                                new TestInput(DataType.STRING, ValueShape.SCALAR),
                                new TestInput(DataType.STRING, ValueShape.SEQUENCE))));

        assertTrue(failure.getMessage().contains("sequence"));
    }

    @Test
    public void rejectsStructuredEventInputDuringInference() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OperatorRegistry.standard().infer(
                        "list_concat",
                        Arrays.asList(
                                new TestInput(DataType.EVENT_SEQUENCE, ValueShape.SEQUENCE),
                                new TestInput(DataType.STRING, ValueShape.SEQUENCE))));

        assertTrue(failure.getMessage().contains("event"));
    }

    @Test
    public void rejectsStructuredEventElementAtRuntime() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OperatorRegistry.standard().evaluate(
                        "list_concat",
                        Arrays.<Object>asList(
                                Collections.singletonList(
                                        Collections.<String, Object>singletonMap("key", "a")),
                                Collections.singletonList("suffix"))));

        assertTrue(failure.getMessage().contains("event"));
        assertTrue(failure.getMessage().contains("index 0"));
    }

    @Test
    public void rejectsStructuredSuffixElementAtRuntime() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OperatorRegistry.standard().evaluate(
                        "list_concat",
                        Arrays.<Object>asList(
                                Collections.singletonList("a"),
                                Collections.singletonList(
                                        Collections.<String, Object>singletonMap("key", "suffix")))));

        assertTrue(failure.getMessage().contains("event"));
        assertTrue(failure.getMessage().contains("suffix"));
    }

    @Test
    public void rejectsNonObjectConfigDuringInference() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OperatorRegistry.standard().infer(
                        "list_concat",
                        Arrays.asList(
                                new TestInput(DataType.STRING, ValueShape.SEQUENCE),
                                new TestInput(DataType.STRING, ValueShape.SEQUENCE),
                                new TestInput(DataType.STRING, ValueShape.SCALAR))));

        assertTrue(failure.getMessage().contains("object config"));
    }

    private static final class TestInput implements OperatorInputMetadata {
        private final DataType dataType;
        private final ValueShape valueShape;

        private TestInput(DataType dataType, ValueShape valueShape) {
            this.dataType = dataType;
            this.valueShape = valueShape;
        }

        @Override public DataType outputType() { return dataType; }
        @Override public Set<EntityScope> entityScopes() {
            return Collections.singleton(EntityScope.USER);
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
