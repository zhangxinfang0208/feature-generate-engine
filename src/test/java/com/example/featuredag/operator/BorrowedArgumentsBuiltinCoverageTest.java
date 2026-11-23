package com.example.featuredag.operator;

import com.example.featuredag.operator.builtin.CalculateSequenceSubOperator;
import com.example.featuredag.operator.builtin.CompareGreaterOperator;
import com.example.featuredag.operator.builtin.CompareSmallerOperator;
import com.example.featuredag.operator.builtin.DivOperator;
import com.example.featuredag.operator.builtin.InitialBusinessOperators;
import com.example.featuredag.operator.builtin.LogBaseOperator;
import com.example.featuredag.operator.builtin.LowerOperator;
import com.example.featuredag.operator.builtin.MaxOperator;
import com.example.featuredag.operator.builtin.MeanOperator;
import com.example.featuredag.operator.builtin.MinOperator;
import com.example.featuredag.operator.builtin.SubOperator;
import com.example.featuredag.operator.builtin.ToBigintOperator;
import com.example.featuredag.operator.builtin.ToIntOperator;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 全部内置算子都复用批适配器的参数容器：逐行 Batch 结果（含失败行的异常类型与消息）必须与 Single 一致。
 * 新增标准算子若忘记声明 BorrowedArgumentsKernel，第一个用例会失败。
 */
public class BorrowedArgumentsBuiltinCoverageTest {

    @Test
    public void everyStandardOperatorReusesTheArgumentContainer() {
        List<String> missing = new ArrayList<>();
        for (OperatorDefinition definition : InitialBusinessOperators.definitions()) {
            if (!(definition instanceof BorrowedArgumentsKernel)) missing.add(definition.name());
        }
        assertEquals(Collections.emptyList(), missing);
    }

    @Test
    public void scalarSequenceAndFailureRowsMatchSingleEvaluation() {
        verify(new SubOperator(), new Object[][]{
                {5, 3}, {2, 5}, {null, 1}, {Arrays.asList(1, 2), 1}, {1.5, 0.25}});
        verify(new DivOperator(), new Object[][]{
                {7, 2}, {1, 0}, {null, 2}, {Arrays.asList(2.0, 4.0), 2.0}});
        verify(new MinOperator(), new Object[][]{
                {3, 1, 2}, {2.5, 2, 9}, {null, 1, 2}, {Arrays.asList(3, 1), 2, 2}});
        verify(new MaxOperator(), new Object[][]{
                {3, 1, 2}, {2.5, 2, 9}, {Arrays.asList(3, 1), 2, 2}});
        verify(new CompareSmallerOperator(), new Object[][]{
                {Arrays.asList(1, 5, 3), 3}, {Arrays.asList(1, 2), 1}, {null, 1}});
        verify(new CompareGreaterOperator(), new Object[][]{
                {Arrays.asList(1, 5, 3), 3}, {null, 1}});
        verify(new CalculateSequenceSubOperator(), new Object[][]{
                {Arrays.asList(1, 4, 9)}, {Arrays.asList(2)}, {null}});
        verify(new MeanOperator(), new Object[][]{
                {Arrays.asList(1, 2, 3)}, {Collections.emptyList()}, {null}});
        verify(new LogBaseOperator(), new Object[][]{
                {8.0, 2.0, 1000.0}, {8.0, 1.0, 1000.0}, {Arrays.asList(8.0, 16.0), 2.0, 1000.0}});
        verify(new ToIntOperator(), new Object[][]{
                {"12"}, {3.9}, {null}, {Arrays.asList("1", "2.9")}});
        verify(new ToBigintOperator(), new Object[][]{
                {"12"}, {3.9}, {null}, {Arrays.asList("1", "2.9")}});
        verify(new LowerOperator(), new Object[][]{
                {"AbC"}, {null}, {Arrays.asList("A", "b")}});
    }

    private static void verify(OperatorDefinition operator, Object[][] rows) {
        assertTrue(operator.name(), operator instanceof BorrowedArgumentsKernel);
        BatchColumn[] columns = new BatchColumn[rows[0].length];
        for (int column = 0; column < columns.length; column++) {
            List<Object> values = new ArrayList<>();
            for (Object[] row : rows) values.add(row[column]);
            columns[column] = new ListBatchColumn(values);
        }
        BatchOperatorResult batch = new SingleLoopBatchOperatorKernel(operator)
                .evaluateBatch(new BatchOperatorCall(new BatchLayout() {
                    public BatchDomain domain() { return BatchDomain.ONLINE_CANDIDATE; }
                    public int rowCount() { return rows.length; }
                    public int groupIndexAt(int row) { return 0; }
                    public int indexInGroupAt(int row) { return row; }
                }, Arrays.asList(columns)));
        for (int row = 0; row < rows.length; row++) {
            Object expected;
            try {
                expected = operator.evaluate(new ArrayList<>(Arrays.asList(rows[row])));
            } catch (RuntimeException error) {
                assertNotNull(operator.name() + " row " + row, batch.rowFailures().get(row));
                assertEquals(operator.name() + " row " + row, error.getClass(), batch.rowFailures().get(row).getClass());
                assertEquals(operator.name() + " row " + row, error.getMessage(),
                        batch.rowFailures().get(row).getMessage());
                continue;
            }
            assertFalse(operator.name() + " row " + row, batch.rowFailures().containsKey(row));
            assertEquals(operator.name() + " row " + row, expected, batch.values().valueAt(row));
        }
    }
}
