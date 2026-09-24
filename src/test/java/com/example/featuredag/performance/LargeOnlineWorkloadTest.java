package com.example.featuredag.performance;

import com.example.featuredag.api.GenerateResult;
import com.example.featuredag.api.OnlineGenerateRequest;
import org.junit.Test;

import java.util.Collections;
import java.util.Map;
import java.util.List;

import static org.junit.Assert.*;

public class LargeOnlineWorkloadTest {
    @Test
    public void allBaseFeaturesAreReachableAndEveryCandidateMatchesIndependentOracle() throws Exception {
        LargeOnlineWorkload workload = new LargeOnlineWorkload(384, 7, 3);
        assertEquals(320, workload.baseCount());
        assertEquals(160, workload.sequenceCount());
        assertEquals(320, workload.targets().size());
        assertEquals(240, workload.request(0).sharedValues().size());
        assertEquals(80, workload.request(0).candidates().get(0).size());
        var engine = workload.engine(false);
        for (int requestIndex = 0; requestIndex < 3; requestIndex++) {
            OnlineGenerateRequest request = workload.request(requestIndex);
            GenerateResult result = engine.generate(request);
            assertEquals(7, result.candidateFeatureValues().size());
            for (int row = 0; row < 7; row++) {
                Map<String, List<?>> actual = result.candidateFeatureValues().get(row);
                assertEquals(320, actual.size());
                for (Map.Entry<String, List<?>> entry : workload.expected(requestIndex, row).entrySet()) {
                    assertEquals(entry.getKey(), entry.getValue(), actual.get(entry.getKey()));
                }
                GenerateResult single = engine.generate(new OnlineGenerateRequest(
                        "single", request.sharedValues(), Collections.singletonList(request.candidates().get(row))));
                assertEquals(actual, single.candidateFeatureValues().get(0));
            }
        }
        String dag = engine.describeLogicalDag();
        for (int i = 0; i < 320; i++) assertTrue("unreachable base " + i, dag.contains(" source=b" + i + "\n"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsEmptyCandidateBatch() throws Exception {
        new LargeOnlineWorkload(384, 0, 1);
    }

    @Test
    public void diagnosticSwitchInstallsAnActualObserver() throws Exception {
        LargeOnlineWorkload workload = new LargeOnlineWorkload(16, 2, 1);
        workload.engine(false).generate(workload.request(0));
        assertNull(workload.latestDiagnostics());
        workload.engine(true).generate(workload.request(0));
        assertNotNull(workload.latestDiagnostics());
    }
}
