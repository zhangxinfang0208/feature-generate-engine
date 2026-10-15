package com.example.featuredag.operator.builtin;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.operator.BorrowedArgumentsKernel;
import com.example.featuredag.operator.OperatorInference;
import com.example.featuredag.operator.OperatorInputMetadata;

import java.util.Collections;
import java.util.List;

/** 取基础类型序列的首元素；只读取逻辑下标 0，Batch 由 SingleLoopBatchOperatorKernel 适配。 */
public final class FirstElementOperator extends AbstractBuiltinOperator implements BorrowedArgumentsKernel {
    public FirstElementOperator() {
        super("first_element", 1, 1, true, true);
    }

    @Override
    public List<String> parameterNames() {
        return Collections.singletonList("sequence");
    }

    @Override
    public OperatorInference infer(List<OperatorInputMetadata> inputs) {
        OperatorInputMetadata input = inputs.get(0);
        if (input.valueShape() != ValueShape.SEQUENCE || input.outputType() == DataType.EVENT_SEQUENCE) {
            throw new IllegalArgumentException("first_element expects a sequence of scalar elements");
        }
        // C6：有类型序列保留元素类型与实体域；字面量集合的 OBJECT 元数据未携带元素类型。
        DataType type = input.outputType() == DataType.OBJECT ? DataType.UNKNOWN : input.outputType();
        return OperatorSupport.fixedInference(inputs, type, ValueShape.SCALAR);
    }

    @Override
    public Object evaluate(List<Object> arguments) {
        Object sequence = arguments.get(0);
        if (OperatorSupport.sequenceSize(sequence, name(), "sequence") == 0) {
            throw new IllegalArgumentException("first_element requires a non-empty sequence");
        }
        Object first = OperatorSupport.sequenceElementAt(sequence, 0, name(), "sequence");
        if (first != null && !(first instanceof Number) && !(first instanceof String)
                && !(first instanceof Boolean)) {
            throw new IllegalArgumentException("first_element expects a scalar first element");
        }
        return first;
    }
}
