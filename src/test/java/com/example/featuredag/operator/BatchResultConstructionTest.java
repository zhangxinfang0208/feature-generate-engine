package com.example.featuredag.operator;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class BatchResultConstructionTest {
    @Test
    public void successfulResultsPreserveNullAndRejectMutation() {
        BatchOperatorResultBuilder builder = new BatchOperatorResultBuilder(2);
        builder.addValue(null);
        builder.addValue("value");
        BatchOperatorResult result = builder.build();
        assertFalse(result.hasFailures());
        assertNull(result.values().valueAt(0));
        assertEquals("value", result.values().valueAt(1));
        assertThrows(UnsupportedOperationException.class,
                () -> result.rowFailures().put(0, new IllegalArgumentException()));
        assertThrows(UnsupportedOperationException.class, () -> ((ListBatchColumn) result.values()).values().clear());
        assertThrows(IllegalStateException.class, () -> builder.addValue("later"));
        assertEquals(0, new BatchOperatorResultBuilder(0).build().values().size());
    }

    @Test
    public void failureMapsKeepDefensiveCopiesAndValidateRows() {
        RuntimeException failure = new IllegalArgumentException("bad");
        Map<Integer, RuntimeException> failures = new LinkedHashMap<>();
        ListBatchColumn values = new ListBatchColumn(Arrays.asList(null, "ok"));
        BatchOperatorResult empty = new BatchOperatorResult(values, failures);
        failures.put(0, failure);
        assertFalse(empty.hasFailures());
        BatchOperatorResult failed = new BatchOperatorResult(values, failures);
        failures.clear();
        assertSame(failure, failed.rowFailures().get(0));
        assertThrows(UnsupportedOperationException.class, () -> failed.rowFailures().clear());
        assertThrows(IllegalArgumentException.class, () -> new BatchOperatorResult(values, Map.of(2, failure)));

        BatchOperatorResultBuilder builder = new BatchOperatorResultBuilder(2);
        assertThrows(NullPointerException.class, () -> builder.addFailure(null));
        builder.addValue("ok");
        builder.addFailure(failure);
        BatchOperatorResult mixed = builder.build();
        assertSame(failure, mixed.rowFailures().get(1));
        assertNull(mixed.values().valueAt(1));
    }
}
