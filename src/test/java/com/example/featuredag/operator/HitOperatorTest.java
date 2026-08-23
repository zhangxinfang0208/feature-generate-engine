package com.example.featuredag.operator;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.definition.EntityScope;
import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.runtime.SequenceBlock;
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

public final class HitOperatorTest {
    @Test
    public void filtersByKeySetAndPreservesSourceOrderAndDuplicates() {
        Map<String, Object> first = event("a", 1);
        Map<String, Object> second = event("b", 2);
        Map<String, Object> third = event("a", 3);
        SequenceBlock events = new SequenceBlock(
                "hit-source", 1L, Arrays.asList(first, second, third));
        OperatorRegistry registry = OperatorRegistry.standard();
        assertTrue("hit must be registered", registry.find("hit").isPresent());

        Object result = registry.evaluate(
                "hit",
                Arrays.<Object>asList(events, Arrays.asList("a", "c", "a")));

        assertEquals(Arrays.asList(first, third), result);
    }

    @Test
    public void scalarBatchAdapterFiltersEachRowIndependently() {
        Map<String, Object> a1 = event("a", 1);
        Map<String, Object> b2 = event("b", 2);
        Map<String, Object> a3 = event("a", 3);
        Map<String, Object> c4 = event("c", 4);
        OperatorRegistry registry = OperatorRegistry.standard();
        BatchOperatorCall call = new BatchOperatorCall(
                new OfflineLayout(2),
                Arrays.<BatchColumn>asList(
                        new ListBatchColumn(Arrays.<Object>asList(
                                Arrays.asList(a1, b2, a3),
                                Arrays.asList(c4, a1))),
                        new ListBatchColumn(Arrays.<Object>asList(
                                Collections.singletonList("a"),
                                Arrays.asList("a", "c")))));

        assertEquals(BatchKernelKind.SCALAR_ADAPTER, registry.batchKernelKind("hit"));
        assertEquals(
                Arrays.asList(Arrays.asList(a1, a3), Arrays.asList(c4, a1)),
                ((ListBatchColumn) registry.evaluateBatch("hit", call).values()).values());
    }

    @Test
    public void requiresEventSequenceAsFirstInputDuringInference() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OperatorRegistry.standard().infer(
                        "hit",
                        Arrays.asList(
                                new TestInput(DataType.STRING, ValueShape.SEQUENCE),
                                new TestInput(DataType.STRING, ValueShape.SEQUENCE))));

        assertTrue(failure.getMessage().contains("EVENT_SEQUENCE"));
    }

    @Test
    public void requiresStringSequenceAsSecondInputDuringInference() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OperatorRegistry.standard().infer(
                        "hit",
                        Arrays.asList(
                                new TestInput(DataType.EVENT_SEQUENCE, ValueShape.SEQUENCE),
                                new TestInput(DataType.INT, ValueShape.SEQUENCE))));

        assertTrue(failure.getMessage().contains("STRING/SEQUENCE"));
    }

    @Test
    public void rejectsEventWithoutKeyField() {
        Map<String, Object> malformed = new LinkedHashMap<String, Object>();
        malformed.put("value", Integer.valueOf(1));

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OperatorRegistry.standard().evaluate(
                        "hit",
                        Arrays.<Object>asList(
                                Collections.singletonList(malformed),
                                Collections.singletonList("a"))));

        assertTrue(failure.getMessage().contains("event at index 0"));
        assertTrue(failure.getMessage().contains("key"));
    }

    @Test
    public void rejectsNonStringQueryKeyWithPosition() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OperatorRegistry.standard().evaluate(
                        "hit",
                        Arrays.<Object>asList(
                                Collections.singletonList(event("a", 1)),
                                Collections.singletonList(Integer.valueOf(1)))));

        assertTrue(failure.getMessage().contains("key at index 0"));
        assertTrue(failure.getMessage().contains("STRING"));
    }

    @Test
    public void rejectsNonStringEventKeyWithPosition() {
        Map<String, Object> malformed = new LinkedHashMap<String, Object>();
        malformed.put("key", Integer.valueOf(1));
        malformed.put("value", "x");

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OperatorRegistry.standard().evaluate(
                        "hit",
                        Arrays.<Object>asList(
                                Collections.singletonList(malformed),
                                Collections.singletonList("a"))));

        assertTrue(failure.getMessage().contains("event key at index 0"));
        assertTrue(failure.getMessage().contains("STRING"));
    }

    @Test
    public void rejectsNonObjectIntermediateEventWithPosition() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OperatorRegistry.standard().evaluate(
                        "hit",
                        Arrays.<Object>asList(
                                Collections.singletonList("not-an-event"),
                                Collections.singletonList("a"))));

        assertTrue(failure.getMessage().contains("event at index 0"));
        assertTrue(failure.getMessage().contains("Map"));
    }

    private static Map<String, Object> event(String key, int value) {
        Map<String, Object> event = new LinkedHashMap<String, Object>();
        event.put("key", key);
        event.put("value", Integer.valueOf(value));
        return event;
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
