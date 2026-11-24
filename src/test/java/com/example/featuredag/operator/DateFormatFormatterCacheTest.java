package com.example.featuredag.operator;

import org.junit.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** date_format 按（模式，时区）缓存 formatter 后，结果与每次新建 formatter 完全一致，异常类型与消息不变。 */
public class DateFormatFormatterCacheTest {
    private static final String[][] PATTERN_ZONES = {
            {"yyMMddHH", "Asia/Shanghai"}, {"yyyy-MM-dd HH:mm:ss.SSS", "UTC"},
            {"yyyy-MM-dd HH", "America/New_York"}, {"HH:mm:ss.SSS", "+00:00"},
            {"yyyy-MM-dd HH:mm:ss.SSS", "Asia/Shanghai"}, {"yyyy", "UTC"}};

    @Test
    public void cachedFormattersMatchFreshlyBuiltOnesOnEveryCall() {
        OperatorRegistry registry = OperatorRegistry.standard();
        List<Object> timestamps = List.of(0L, 1719835200123L, -1001L, 1234, 253402300799999L);
        // 两轮：第二轮全部命中缓存。
        for (int round = 0; round < 2; round++) {
            for (String[] patternZone : PATTERN_ZONES) {
                for (Object timestamp : timestamps) {
                    boolean defaultZone = patternZone[1].equals("Asia/Shanghai");
                    Object actual = defaultZone
                            ? registry.evaluate("date_format", List.of(timestamp, patternZone[0]))
                            : registry.evaluate("date_format", List.of(timestamp, patternZone[0], patternZone[1]));
                    String expected = DateTimeFormatter.ofPattern(patternZone[0], Locale.ROOT)
                            .withZone(ZoneId.of(patternZone[1]))
                            .format(Instant.ofEpochMilli(((Number) timestamp).longValue()));
                    assertEquals(expected, actual);
                }
            }
        }
    }

    @Test
    public void patternsBeyondTheCacheLimitStillFormatCorrectly() {
        OperatorRegistry registry = OperatorRegistry.standard();
        for (int hour = 0; hour < 100; hour++) {
            // 100 个不同模式 × 2 个时区，远超缓存上限；超限的组合不进缓存，但结果必须一致。
            String pattern = "yyyy-MM-dd '" + hour + "' HH";
            for (String zone : List.of("UTC", "Asia/Shanghai")) {
                String expected = DateTimeFormatter.ofPattern(pattern, Locale.ROOT)
                        .withZone(ZoneId.of(zone)).format(Instant.ofEpochMilli(0L));
                assertEquals(expected, registry.evaluate("date_format", List.of(0L, pattern, zone)));
                assertEquals(expected, registry.evaluate("date_format", List.of(0L, pattern, zone)));
            }
        }
    }

    @Test
    public void invalidZonesAndPatternsKeepTheirExceptionsOnEveryCall() {
        OperatorRegistry registry = OperatorRegistry.standard();
        for (int round = 0; round < 2; round++) {
            IllegalArgumentException unknownZone = assertThrows(IllegalArgumentException.class,
                    () -> registry.evaluate("date_format", List.of(0L, "HH", "unknown")));
            assertTrue(unknownZone.getMessage(), unknownZone.getMessage().startsWith("date_format: "));
            IllegalArgumentException badPattern = assertThrows(IllegalArgumentException.class,
                    () -> registry.evaluate("date_format", List.of(0L, "HH'")));
            IllegalArgumentException repeated = assertThrows(IllegalArgumentException.class,
                    () -> registry.evaluate("date_format", List.of(0L, "HH'")));
            assertEquals(badPattern.getMessage(), repeated.getMessage());
            assertThrows(IllegalArgumentException.class,
                    () -> registry.evaluate("date_format", List.of(0L, "HH", 42)));
        }
    }

    @Test
    public void concurrentFormattingSharesTheCacheSafely() throws Exception {
        OperatorRegistry registry = OperatorRegistry.standard();
        List<Callable<Void>> calls = new ArrayList<>();
        for (int index = 0; index < 32; index++) {
            String[] patternZone = PATTERN_ZONES[index % PATTERN_ZONES.length];
            long timestamp = index * 86_400_000L - 86_400_000L;
            calls.add(() -> {
                for (int repeat = 0; repeat < 200; repeat++) {
                    String expected = DateTimeFormatter.ofPattern(patternZone[0], Locale.ROOT)
                            .withZone(ZoneId.of(patternZone[1])).format(Instant.ofEpochMilli(timestamp));
                    assertEquals(expected, registry.evaluate(
                            "date_format", List.of(timestamp, patternZone[0], patternZone[1])));
                }
                return null;
            });
        }
        try (var pool = Executors.newFixedThreadPool(4)) {
            for (var future : pool.invokeAll(calls)) future.get();
        }
    }
}
