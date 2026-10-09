package com.example.featuredag.performance;

import org.junit.Test;
import static org.junit.Assert.*;

public class LargeOfflineWorkloadTest {
    @Test
    public void independentRowsMatchOracleInSingleAndBatch() throws Exception {
        LargeOfflineWorkload workload = new LargeOfflineWorkload(384, 3, 2);
        assertEquals(320, workload.rows(0).get(0).size());
        assertEquals(384, workload.rows(0).get(0).get("b0").size());
        assertEquals(96, workload.rows(0).get(0).get("b1").size());
        assertNotSame(workload.rows(0).get(0).get("b0"), workload.rows(0).get(1).get("b0"));
        assertNotEquals(workload.rows(0).get(0), workload.rows(0).get(1));
        assertNotEquals(workload.rows(0), workload.rows(1));
        var engine = workload.engine();
        LargeOfflineBenchmark.verify(workload, engine, 0);
        LargeOfflineBenchmark.verify(workload, engine, 1);
    }

    @Test
    public void singleRowAndShortSequenceAreValid() throws Exception {
        LargeOfflineWorkload workload = new LargeOfflineWorkload(1, 1, 1);
        LargeOfflineBenchmark.verify(workload, workload.engine(), 0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsEmptyBatch() throws Exception {
        new LargeOfflineWorkload(384, 0, 1);
    }
}
