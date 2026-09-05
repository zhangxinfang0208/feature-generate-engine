package com.example.featuredag.api;

import com.example.featuredag.config.FeatureOutputDescriptor;
import com.example.featuredag.definition.*;
import com.example.featuredag.expression.ExpressionParser;
import com.example.featuredag.logical.LogicalDagBuilder;
import com.example.featuredag.operator.OperatorRegistry;
import com.example.featuredag.runtime.*;
import org.junit.Test;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

public class SequenceOutputMaterializationTest {
    @Test
    public void truncationReadsOnlyTheReturnedPrefix() {
        CountingList input = new CountingList(100_000);
        assertEquals(List.of(0, 1, 2), encoder(3).encodeBatchElement("seq", input));
        assertEquals("Discarded elements should not be materialized", 3, input.reads);
    }

    @Test
    public void scalarWrappedSequenceAlsoReadsOnlyTheReturnedPrefix() {
        CountingList input = new CountingList(100_000);
        assertEquals(List.of(0, 1, 2), encoder(3).encode("seq", new ScalarValue(input)));
        assertEquals(3, input.reads);
    }

    @Test
    public void nestedListsAreFullyMaterializedWithinTheReturnedPrefix() {
        List<Object> nested = new ArrayList<>(Arrays.asList(1, null, 3, 4));
        List<?> result = encoder(1).encodeBatchElement("seq", List.of(Map.of("values", nested), "discard"));
        nested.clear();
        assertEquals(List.of(Map.of("values", Arrays.asList(1, null, 3, 4))), result);
        assertThrows(UnsupportedOperationException.class, result::clear);
        List<?> nestedResult = (List<?>) ((Map<?, ?>) result.get(0)).get("values");
        assertThrows(UnsupportedOperationException.class, nestedResult::clear);
    }

    @Test
    public void sequenceHandlesRespectViewOrderAndDefaultPadding() {
        SequenceBlock block = new SequenceBlock("output-prefix", 1,
                List.of(Map.of("key", "a"), Map.of("key", "b"), Map.of("key", "a")));
        SequenceView view = SequenceView.filterByColumn(block, "key", "a");
        assertEquals(List.of(Map.of("key", "a")), encoder(1).encode("seq", view));
        assertEquals(Arrays.asList(Map.of("key", "a"), Map.of("key", "a"), null),
                encoder(3).encode("seq", view));
        assertEquals(Arrays.asList(1, null, null), encoder(3).encode("seq",
                new ListSequenceValue("list", Arrays.asList(1, null))));
        assertEquals(Arrays.asList(null, null, null), encoder(3).encodeBatchElement("seq", List.of()));
    }

    @Test
    public void unlimitedEncodingReturnsAnImmutableIndependentResult() {
        List<Object> input = new ArrayList<>(Arrays.asList(1, null, 2));
        List<?> result = encoder(null).encodeBatchElement("seq", input);
        input.clear();
        assertEquals(Arrays.asList(1, null, 2), result);
        assertThrows(UnsupportedOperationException.class, result::clear);
    }

    @Test
    public void publicApiPreservesUpstreamLengthAcrossSingleAndBatchModes() {
        String config = """
                {"feature_set_name":"bounded-output","version":"1","features":[
                  {"name":"raw","raw_name":"raw","type":"BIGINT","definition_type":"BASE",
                   "value_shape":"SEQUENCE","entity_scopes":["ITEM"]},
                  {"name":"seq","type":"BIGINT","definition_type":"DERIVED","expression":"raw",
                   "value_shape":"SEQUENCE","seq_max_length":2,"dft":-1,"output_policy":"OUTPUT"},
                  {"name":"full_length","type":"INT","definition_type":"DERIVED",
                   "expression":"get_seq_length(seq)","value_shape":"SCALAR","output_policy":"OUTPUT"}
                ]}
                """;
        Map<String, List<?>> longRow = Map.of("raw", List.of(1, 2L, 3L, 4L));
        Map<String, List<?>> shortRow = Map.of("raw", List.of(9L));
        FeatureDagEngine offline = FeatureDagEngine.init(config, InitOptions.offline("bounded-offline"));
        GenerateResult single = offline.generate(new OfflineGenerateRequest("single", longRow));
        assertEquals(List.of(1L, 2L), single.featureValues().get("seq"));
        assertEquals(List.of(4), single.featureValues().get("full_length"));
        OfflineBatchGenerateResult batch = offline.generateBatch(
                new OfflineBatchGenerateRequest("batch", List.of(longRow, shortRow)));
        assertEquals(single.featureValues(), batch.rows().get(0));
        assertEquals(List.of(9L, -1L), batch.rows().get(1).get("seq"));
        assertEquals(List.of(1), batch.rows().get(1).get("full_length"));

        FeatureDagEngine online = FeatureDagEngine.init(config, InitOptions.online("bounded-online"));
        GenerateResult candidates = online.generate(new OnlineGenerateRequest(
                "online", Map.of(), List.of(longRow, shortRow)));
        assertEquals(batch.rows(), candidates.candidateFeatureValues());
        OnlineBatchGenerateResult groups = online.generateBatch(new OnlineBatchGenerateRequest(
                "groups", List.of(new OnlineRequestGroup("first", Map.of(), List.of(longRow, shortRow)),
                        new OnlineRequestGroup("second", Map.of(), List.of(shortRow)))));
        assertEquals(batch.rows(), groups.groupResults().get(0).candidateFeatureValues());
        assertEquals(batch.rows().get(1), groups.groupResults().get(1).candidateFeatureValues().get(0));
    }

    @Test
    public void eventSequenceEncodingKeepsAnonymousAndNamedIntermediateValues() {
        String config = """
                {"feature_set_name":"event-prefix","version":"1","features":[
                  {"name":"events","raw_name":"events","type":"EVENT_SEQUENCE","definition_type":"BASE",
                   "value_shape":"SEQUENCE","entity_scopes":["USER"]},
                  {"name":"selected","type":"EVENT_SEQUENCE","definition_type":"DERIVED",
                   "expression":"slice_by_indices(events, [2, 0])","value_shape":"SEQUENCE",
                   "output_policy":"INTERNAL_ONLY"},
                  {"name":"named","type":"EVENT_SEQUENCE","definition_type":"DERIVED",
                   "expression":"selected","value_shape":"SEQUENCE","seq_max_length":1,"output_policy":"OUTPUT"},
                  {"name":"anonymous","type":"EVENT_SEQUENCE","definition_type":"DERIVED",
                   "expression":"slice_by_indices(slice_by_indices(events, [2, 0]), [0, 1])",
                   "value_shape":"SEQUENCE","seq_max_length":1,"output_policy":"OUTPUT"},
                  {"name":"size","type":"INT","definition_type":"DERIVED",
                   "expression":"get_seq_length(selected)","value_shape":"SCALAR","output_policy":"OUTPUT"}
                ]}
                """;
        Map<String, List<?>> input = Map.of("events", List.of(Map.of("key", "a"), Map.of("key", "b"),
                Map.of("key", "c", "tags", List.of(1, 2, 3))));
        FeatureDagEngine engine = FeatureDagEngine.init(config, InitOptions.offline("events-offline"));
        Map<String, List<?>> single = engine.generate(new OfflineGenerateRequest("single", input)).featureValues();
        assertEquals(List.of(Map.of("key", "c", "tags", List.of(1, 2, 3))), single.get("named"));
        assertEquals(single.get("named"), single.get("anonymous"));
        assertEquals(List.of(2), single.get("size"));
        assertEquals(List.of(single, single), engine.generateBatch(
                new OfflineBatchGenerateRequest("batch", List.of(input, input))).rows());
    }

    private static FeatureOutputEncoder encoder(Integer limit) {
        FeatureDefinition source = FeatureDefinition.builder().name("seq").role(FeatureRole.RAW)
                .dataType(DataType.INT).addEntityScope(EntityScope.USER).sourceBinding("seq")
                .declaredValueShape(ValueShape.SEQUENCE).build();
        return FeatureOutputEncoder.from(new LogicalDagBuilder(new ExpressionParser(), OperatorRegistry.standard())
                .build(List.of(source), Set.of("seq")),
                List.of(new FeatureOutputDescriptor("seq", "seq", 0, 0, limit)));
    }

    private static final class CountingList extends AbstractList<Integer> {
        private final int size;
        private int reads;
        private CountingList(int size) { this.size = size; }
        @Override public int size() { return size; }
        @Override public Integer get(int index) {
            java.util.Objects.checkIndex(index, size);
            reads++;
            return index;
        }
    }
}
