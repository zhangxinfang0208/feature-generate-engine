package com.example.featuredag.operator;

import com.example.featuredag.runtime.SequenceBlock;
import com.example.featuredag.runtime.SequenceView;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * 首期算子对事件序列视图（OperatorSequence）的消费能力与直通求值（JUnit 4）。
 * 覆盖 supportsSequenceView 声明表、Single 直通求值、Native Batch 求值，
 * 以及 zip_concat / calc_delta_seq 对事件 Map 元素的元素级拒绝。
 */
public final class SequenceViewOperatorSupportTest {
    @Test
    public void sequenceViewCapabilitiesAndDirectEvaluation() {
        OperatorRegistry registry = OperatorRegistry.standard();
        Map<String, Boolean> expected = Map.ofEntries(
                Map.entry("discrete", true),
                Map.entry("log_base", true),
                Map.entry("slice_by_indices", true),
                Map.entry("find_indices", true),
                Map.entry("get_seq_length", true),
                Map.entry("count_distinct", true),
                Map.entry("zip_concat", true),
                Map.entry("list_concat", true),
                Map.entry("hit", true),
                Map.entry("calc_delta_seq", false),
                Map.entry("to_int", true),
                Map.entry("to_bigint", true));
        for (Map.Entry<String, Boolean> entry : expected.entrySet()) {
            assertEquals(
                    entry.getKey() + " supportsSequenceView",
                    entry.getValue(),
                    registry.require(entry.getKey()).supportsSequenceView());
        }

        Map<String, Object> first = Map.of(
                "itemId", "item-1", "industryId", "keep", "timestamp", 1L,
                "eventType", "click", "value", 1.0, "score", 9.0);
        Map<String, Object> second = Map.of(
                "itemId", "item-2", "industryId", "drop", "timestamp", 2L,
                "eventType", "view", "value", 2.0);
        Map<String, Object> third = Map.of(
                "itemId", "item-3", "industryId", "keep", "timestamp", 3L,
                "eventType", "click", "value", 3.0);
        SequenceBlock block = new SequenceBlock(
                "sequence-view-capabilities", 1L, List.of(first, second, third));
        SequenceView view = SequenceView.filterByColumn(block, "industryId", "keep");

        assertEquals(Integer.valueOf(2), registry.evaluate("get_seq_length", List.of(view)));
        assertEquals(Integer.valueOf(2), registry.evaluate("count_distinct", List.of(view)));
        assertEquals(List.of(1), registry.evaluate("find_indices", List.of(view, third)));
        assertEquals(List.of(third), registry.evaluate("slice_by_indices", List.of(view, List.of(1))));

        Map<String, Object> hitFirst = Map.of("key", "a", "selected", true, "value", 1);
        Map<String, Object> hitSecond = Map.of("key", "b", "selected", false, "value", 2);
        Map<String, Object> hitThird = Map.of("key", "a", "selected", true, "value", 3);
        SequenceView hitView = SequenceView.filterByColumn(
                new SequenceBlock(
                        "hit-sequence-view",
                        1L,
                        List.of(hitFirst, hitSecond, hitThird)),
                "selected",
                true);
        assertEquals(
                List.of(hitFirst, hitThird),
                registry.evaluate("hit", List.of(hitView, List.of("a"))));

        // 当前 SequenceView 承载事件 Map；list_concat 能直接消费视图并给出元素级拒绝。
        IllegalArgumentException listConcatFailure = assertThrows(
                IllegalArgumentException.class,
                () -> registry.evaluate("list_concat", List.of(view, List.of("suffix"))));
        assertTrue(listConcatFailure.getMessage().contains("event"));

        // 事件元素无既定字符串契约：zip_concat 拒绝拼接，避免把事件结构 dump 固化为特征值。
        IllegalArgumentException zipFailure = assertThrows(
                IllegalArgumentException.class,
                () -> registry.evaluate("zip_concat", List.of(view, view)));
        assertTrue(zipFailure.getMessage().contains("event"));

        // calc_delta_seq 不做隐式数值投影：事件序列保持拒绝并给出明确错误。
        IllegalArgumentException deltaFailure = assertThrows(
                IllegalArgumentException.class,
                () -> registry.evaluate("calc_delta_seq", List.of(List.of(first, second), 1.0)));
        assertTrue(deltaFailure.getMessage().contains("numeric"));

        BatchOperatorCall call = new BatchOperatorCall(
                new FixedBatchLayout(BatchDomain.OFFLINE_ROW, 2),
                List.of(
                        new ListBatchColumn(List.of(view, view)),
                        new ListBatchColumn(List.of(first, third))));
        assertEquals(
                List.of(0),
                registry.evaluateBatch("find_indices", call, BatchKernelKind.NATIVE)
                        .values().valueAt(0));
        assertEquals(
                List.of(1),
                registry.evaluateBatch("find_indices", call, BatchKernelKind.NATIVE)
                        .values().valueAt(1));
    }

    private record FixedBatchLayout(
            BatchDomain domain,
            int rowCount) implements BatchLayout {
        @Override
        public int groupIndexAt(int rowIndex) {
            return domain == BatchDomain.ONLINE_CANDIDATE ? rowIndex / 2 : -1;
        }

        @Override
        public int indexInGroupAt(int rowIndex) {
            return domain == BatchDomain.ONLINE_CANDIDATE ? rowIndex % 2 : rowIndex;
        }
    }
}
