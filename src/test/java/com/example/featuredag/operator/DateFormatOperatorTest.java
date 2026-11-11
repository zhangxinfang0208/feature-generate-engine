package com.example.featuredag.operator;

import com.example.featuredag.api.FeatureDagEngine;
import com.example.featuredag.api.InitOptions;
import com.example.featuredag.api.OfflineGenerateRequest;
import com.example.featuredag.physical.ExecutionEnvironment;
import com.example.featuredag.definition.DataType;
import com.example.featuredag.definition.EntityScope;
import com.example.featuredag.definition.ValueShape;
import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

public final class DateFormatOperatorTest {
    private final OperatorRegistry registry = OperatorRegistry.standard();

    @Test
    public void registersAndInfersScalarString() {
        OperatorDefinition definition = registry.require("date_format");
        assertTrue(definition.deterministic());
        assertTrue(definition.sideEffectFree());
        assertEquals(BatchKernelKind.SCALAR_ADAPTER, registry.batchKernelKind("date_format"));
        Input timestamp = new Input(DataType.BIGINT, Set.of(EntityScope.ITEM), ValueShape.CANDIDATE_VECTOR);
        Input pattern = new Input(DataType.STRING, Set.of(), ValueShape.SCALAR);
        OperatorInference inferred = registry.infer("date_format", List.of(timestamp, pattern));
        assertEquals(DataType.STRING, inferred.outputType());
        assertEquals(ValueShape.SCALAR, inferred.valueShape());
        assertEquals(Set.of(EntityScope.ITEM), inferred.entityScopes());
        assertThrows(IllegalArgumentException.class,
                () -> registry.infer("date_format", List.of(pattern, pattern)));
    }

    @Test
    public void formatsTruncatedSecondsInRequestedZone() {
        assertEquals("70010108", registry.evaluate("date_format", List.of(0L, "yyMMddHH")));
        assertEquals("70010100", registry.evaluate("date_format", List.of(-999, "yyMMddHH", "UTC")));
        assertEquals("69123123", registry.evaluate("date_format", List.of(-1000, "yyMMddHH", "+00:00")));
        assertEquals("2024-07-01 08", registry.evaluate("date_format",
                List.of(1719835200000L, "yyyy-MM-dd HH", "America/New_York")));
        assertThrows(IllegalArgumentException.class,
                () -> registry.evaluate("date_format", List.of(0, "HH", "unknown")));
        assertThrows(IllegalArgumentException.class,
                () -> registry.evaluate("date_format", List.of(0, "HH'")));
    }

    @Test
    public void formatsStringTimestampThroughPublicApi() {
        String config = """
                {"feature_set_name":"dates","version":"1","features":[
                  {"name":"timestamp_s","raw_name":"timestamp_s","type":"STRING",
                   "definition_type":"BASE","entity_scopes":["USER"],"value_shape":"SCALAR"},
                  {"name":"hour","type":"STRING","definition_type":"DERIVED",
                   "expression":"date_format(to_bigint(timestamp_s), \\"yyMMddHH\\")",
                   "output_policy":"OUTPUT","entity_scopes":["USER"],"value_shape":"SCALAR"}]}
                """;
        FeatureDagEngine engine = FeatureDagEngine.init(config,
                InitOptions.builder().environment(ExecutionEnvironment.OFFLINE).build());
        assertEquals(List.of("70010108"), engine.generate(new OfflineGenerateRequest(
                "row", Map.of("timestamp_s", List.of("0")))).featureValues().get("hour"));
    }

    private record Input(DataType outputType, Set<EntityScope> entityScopes,
                         ValueShape valueShape) implements OperatorInputMetadata {
        @Override public String sourceFeatureName() { return null; }
    }
}
