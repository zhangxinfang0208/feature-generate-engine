package com.example.featuredag.operator.builtin;

import com.example.featuredag.definition.DataType;
import com.example.featuredag.definition.ValueShape;
import com.example.featuredag.operator.BorrowedArgumentsKernel;
import com.example.featuredag.operator.OperatorInference;
import com.example.featuredag.operator.OperatorInputMetadata;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Unix 毫秒时间戳按指定时区和模式格式化，保留毫秒精度。 */
public final class DateFormatOperator extends AbstractBuiltinOperator implements BorrowedArgumentsKernel {
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");
    /** 缓存上限：模式和时区通常是少量常量，超过上限的新组合照常格式化但不缓存。 */
    private static final int FORMATTER_CACHE_LIMIT = 64;
    /** 模式 → (时区 → formatter)。DateTimeFormatter 不可变且线程安全，缓存不属于任何请求状态。 */
    private static final Map<String, Map<String, DateTimeFormatter>> FORMATTERS =
            new ConcurrentHashMap<String, Map<String, DateTimeFormatter>>();
    private static final AtomicInteger CACHED_FORMATTERS = new AtomicInteger();

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
        String zoneText = arguments.size() == 3
                ? stringArgument(arguments.get(2), "zone") : DEFAULT_ZONE.getId();
        try {
            long timestampMs = ((Number) timestamp).longValue();
            return formatter(pattern, zoneText).format(Instant.ofEpochMilli(timestampMs));
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("date_format: " + e.getMessage(), e);
        }
    }

    /** 按模式和时区取 formatter；解析模式与时区的异常与未缓存时完全相同。 */
    private static DateTimeFormatter formatter(String pattern, String zoneText) {
        Map<String, DateTimeFormatter> byZone = FORMATTERS.get(pattern);
        if (byZone != null) {
            DateTimeFormatter cached = byZone.get(zoneText);
            if (cached != null) return cached;
        }
        DateTimeFormatter created = DateTimeFormatter.ofPattern(pattern, Locale.ROOT)
                .withZone(ZoneId.of(zoneText));
        if (CACHED_FORMATTERS.get() < FORMATTER_CACHE_LIMIT) {
            Map<String, DateTimeFormatter> zones = FORMATTERS.computeIfAbsent(
                    pattern, ignored -> new ConcurrentHashMap<String, DateTimeFormatter>());
            if (zones.putIfAbsent(zoneText, created) == null) CACHED_FORMATTERS.incrementAndGet();
        }
        return created;
    }

    private static String stringArgument(Object value, String name) {
        if (!(value instanceof String)) {
            throw new IllegalArgumentException("date_format " + name + " must be STRING");
        }
        return (String) value;
    }
}
