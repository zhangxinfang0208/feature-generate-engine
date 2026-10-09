package com.example.featuredag.performance;

import com.example.featuredag.api.FeatureDagEngine;
import com.example.featuredag.api.InitOptions;
import com.example.featuredag.api.OfflineBatchGenerateRequest;
import com.example.featuredag.api.OfflineGenerateRequest;
import com.example.featuredag.physical.ExecutionEnvironment;
import com.example.featuredag.runtime.ObservabilityOptions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** 离线全行输入；行间不共享序列对象，不模拟在线共享人场特征。 */
public final class LargeOfflineWorkload {
    private final String config;
    private final List<List<Map<String, List<?>>>> inputs = new ArrayList<>();
    private final List<OfflineBatchGenerateRequest> batches = new ArrayList<>();
    private final List<List<OfflineGenerateRequest>> singles = new ArrayList<>();
    private final List<OfflineBatchGenerateRequest> borrowedBatches = new ArrayList<>();
    private final List<List<OfflineGenerateRequest>> borrowedSingles = new ArrayList<>();

    public LargeOfflineWorkload(int longLength, int batchSize, int poolSize) throws Exception {
        if (longLength < 1 || batchSize < 1 || poolSize < 1) {
            throw new IllegalArgumentException("length, batchSize and poolSize must be positive");
        }
        // 仅复用表达式配置：320 BASE / 320 DERIVED，全部 BASE 参与计算。
        // 在线 fixture 的一条输入不进入离线测量，也不进入离线引擎。
        config = new LargeOnlineWorkload(1, 1, 1).config()
                .replace("large_online_performance", "large_offline_performance");
        for (int pool = 0; pool < poolSize; pool++) {
            List<Map<String, List<?>>> rows = new ArrayList<>(batchSize);
            List<OfflineGenerateRequest> rowRequests = new ArrayList<>(batchSize);
            for (int row = 0; row < batchSize; row++) {
                int seed = pool * batchSize + row;
                Map<String, List<?>> values = new LinkedHashMap<>();
                for (int feature = 0; feature < 320; feature++) {
                    List<Integer> sequence = new ArrayList<>();
                    int length = feature < 160 ? (feature % 2 == 0 ? longLength : Math.max(1, longLength / 4)) : 1;
                    for (int j = 0; j < length; j++) {
                        sequence.add(1 + Math.floorMod(j * 17 + feature * 7 + seed * 11, 64));
                    }
                    values.put("b" + feature, Collections.unmodifiableList(sequence));
                }
                rows.add(Collections.unmodifiableMap(values));
                rowRequests.add(new OfflineGenerateRequest("row-" + seed, values));
            }
            inputs.add(Collections.unmodifiableList(rows));
            batches.add(new OfflineBatchGenerateRequest("batch-" + pool, rows));
            singles.add(rowRequests);
            borrowedBatches.add(OfflineBatchGenerateRequest.borrowed("borrowed-" + pool, rows));
            List<OfflineGenerateRequest> borrowedRows = new ArrayList<>(batchSize);
            for (Map<String, List<?>> row : rows) borrowedRows.add(OfflineGenerateRequest.borrowed("borrowed-row", row));
            borrowedSingles.add(borrowedRows);
        }
    }

    public FeatureDagEngine engine() {
        return FeatureDagEngine.init(config, InitOptions.builder()
                .environment(ExecutionEnvironment.OFFLINE).planId("large-offline-performance")
                .observabilityOptions(ObservabilityOptions.builder().enabled(false).build()).build());
    }

    public String config() { return config; }
    public List<Map<String, List<?>>> rows(int index) { return inputs.get(Math.floorMod(index, inputs.size())); }
    public OfflineBatchGenerateRequest batch(int index) { return batches.get(Math.floorMod(index, batches.size())); }
    public List<OfflineGenerateRequest> singles(int index) { return singles.get(Math.floorMod(index, singles.size())); }
    public OfflineBatchGenerateRequest borrowedBatch(int index) { return borrowedBatches.get(Math.floorMod(index, borrowedBatches.size())); }
    public List<OfflineGenerateRequest> borrowedSingles(int index) { return borrowedSingles.get(Math.floorMod(index, borrowedSingles.size())); }

    /** 独立 Java oracle，不调用任何引擎算子。 */
    public Map<String, List<?>> expected(Map<String, List<?>> row) {
        Map<String, List<?>> result = new LinkedHashMap<>();
        for (int i = 0; i < 320; i++) {
            int item = (Integer) row.get("b" + (240 + i % 80)).get(0);
            List<?> input = row.get("b" + i);
            if (i < 160) {
                List<Object> matches = new ArrayList<>();
                for (Object value : input) if (((Integer) value).intValue() == item) matches.add(value);
                switch (i % 4) {
                    case 0: result.put("d" + i, Collections.singletonList(matches.size())); break;
                    case 1: result.put("d" + i, Collections.singletonList(matches.isEmpty() ? 0 : 1)); break;
                    case 2: result.put("d" + i, Collections.singletonList((long) new LinkedHashSet<>(input).size() + item)); break;
                    default: result.put("d" + i, matches); break;
                }
            } else {
                result.put("d" + i, Collections.singletonList((long) (Integer) input.get(0) * 2 + item));
            }
        }
        return result;
    }
}
