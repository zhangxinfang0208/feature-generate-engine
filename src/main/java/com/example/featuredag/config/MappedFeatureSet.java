package com.example.featuredag.config;

import com.example.featuredag.definition.FeatureDefinition;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public record MappedFeatureSet(
        String featureSetName,
        String version,
        List<FeatureDefinition> definitions,
        Set<String> targetFeatures,
        List<FeatureOutputDescriptor> outputs,
        Set<String> unresolvedOnlineScopes) {
    public MappedFeatureSet {
        definitions = List.copyOf(definitions);
        targetFeatures = Collections.unmodifiableSet(new LinkedHashSet<>(targetFeatures));
        outputs = List.copyOf(outputs);
        unresolvedOnlineScopes = Collections.unmodifiableSet(
                new LinkedHashSet<>(unresolvedOnlineScopes));
    }
}
