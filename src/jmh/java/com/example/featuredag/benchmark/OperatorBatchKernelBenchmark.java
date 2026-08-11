package com.example.featuredag.benchmark;

import com.example.featuredag.operator.BatchColumn;
import com.example.featuredag.operator.BatchDomain;
import com.example.featuredag.operator.BatchKernelKind;
import com.example.featuredag.operator.BatchLayout;
import com.example.featuredag.operator.BatchOperatorCall;
import com.example.featuredag.operator.BatchOperatorResult;
import com.example.featuredag.operator.ListBatchColumn;
import com.example.featuredag.operator.OperatorRegistry;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** 对比同一 add 语义的共用行级 Native Kernel 与 Single-loop Batch Adapter。 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 8, time = 1)
@Fork(2)
public class OperatorBatchKernelBenchmark {
    @Benchmark
    public BatchOperatorResult nativeBatch(AddBatchState state) {
        return state.registry.evaluateBatch(
                "add", state.call, BatchKernelKind.NATIVE);
    }

    @Benchmark
    public BatchOperatorResult scalarAdapter(AddBatchState state) {
        return state.registry.evaluateBatch(
                "add", state.call, BatchKernelKind.SCALAR_ADAPTER);
    }

    @State(Scope.Benchmark)
    public static class AddBatchState {
        @Param({"1", "10", "100", "1000"})
        public int rowCount;

        private OperatorRegistry registry;
        private BatchOperatorCall call;

        @Setup(Level.Trial)
        public void setup() {
            registry = OperatorRegistry.standard();
            List<Object> left = new ArrayList<>(rowCount);
            List<Object> right = new ArrayList<>(rowCount);
            for (int rowIndex = 0; rowIndex < rowCount; rowIndex++) {
                left.add((double) rowIndex);
                right.add((double) rowIndex + 1.0);
            }
            List<BatchColumn> columns = List.of(
                    new ListBatchColumn(left),
                    new ListBatchColumn(right));
            call = new BatchOperatorCall(new OfflineLayout(rowCount), columns);
        }
    }

    private record OfflineLayout(int rowCount) implements BatchLayout {
        @Override public BatchDomain domain() { return BatchDomain.OFFLINE_ROW; }
        @Override public int groupIndexAt(int rowIndex) { return -1; }
        @Override public int indexInGroupAt(int rowIndex) { return rowIndex; }
    }
}
