package com.example.featuredag.planning;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public record NodePlanningMetadata(
        int referenceCount,
        Set<String> reachableRootNodeIds,
        List<String> reuseKeyInputs,
        String fusionCandidate,
        String indexCandidate,
        long estimatedCost,
        long estimatedSizeBytes) {

    public NodePlanningMetadata {
        reachableRootNodeIds = Collections.unmodifiableSet(new LinkedHashSet<>(reachableRootNodeIds));
        reuseKeyInputs = List.copyOf(reuseKeyInputs);
    }

    public static NodePlanningMetadata basic(int referenceCount, Set<String> roots) {
        return new NodePlanningMetadata(referenceCount, roots, List.of(), null, null, 1L, 8L);
    }
}
