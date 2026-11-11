package com.example.featuredag.operator.builtin;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.operator.OperatorInference;
import com.example.featuredag.operator.OperatorInputMetadata;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** Unix 毫秒时间戳按指定时区和模式格式化，保留毫秒精度。 */
public final class DateFormatOperator extends AbstractBuiltinOperator {
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");

    public DateFormatOperator() {
        super("date_format", 2, 3, true, false);
    }

    @Override
    public OperatorInference infer(List<OperatorInputMetadata> inputs) {
        for (int index = 0; index < inputs.size(); index++) {
            OperatorInputMetadata input = inputs.get(index);
            if (input.valueShape() != ValueShape.SCALAR && input.valueShape() != ValueShape.CANDIDATE_VECTOR) {
                throw new IllegalArgumentException("date_format requires scalar arguments");
            }
            DataType type = input.outputType();
            if (index == 0 && type != DataType.INT && type != DataType.BIGINT) {
                throw new IllegalArgumentException("date_format requires INT or BIGINT milliseconds");
            }
            if (index > 0 && type != DataType.STRING) {
                throw new IllegalArgumentException("date_format pattern and zone must be STRING");
            }
        }
        // C6/C10：每行输出字符串，实体域包含所有参与格式化的输入。
        return OperatorSupport.fixedInference(inputs, DataType.STRING, ValueShape.SCALAR);
    }

    @Override
    public Object evaluate(List<Object> arguments) {
        Object timestamp = arguments.get(0);
        if (!(timestamp instanceof Long || timestamp instanceof Integer)) {
            throw new IllegalArgumentException("date_format requires INT or BIGINT milliseconds");
        }
        String pattern = stringArgument(arguments.get(1), "pattern");
        try {
            ZoneId zone = arguments.size() == 3
                    ? ZoneId.of(stringArgument(arguments.get(2), "zone")) : DEFAULT_ZONE;
            long timestampMs = ((Number) timestamp).longValue();
            return DateTimeFormatter.ofPattern(pattern, Locale.ROOT).withZone(zone)
                    .format(Instant.ofEpochMilli(timestampMs));
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("date_format: " + e.getMessage(), e);
        }
    }

    private static String stringArgument(Object value, String name) {
        if (!(value instanceof String)) {
            throw new IllegalArgumentException("date_format " + name + " must be STRING");
        }
        return (String) value;
    }
}
