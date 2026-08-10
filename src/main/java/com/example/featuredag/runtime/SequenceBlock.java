package com.example.featuredag.runtime;

import java.util.List;
import java.util.Objects;

/**
 * Immutable columnar base sequence.
 *
 * 序列底层块：按列存储（item/industry/timestamp/eventType/value），
 * 是所有序列视图（SequenceView）共享的数据载体；handleKey 用于缓存标识。
 */
public final class SequenceBlock implements SequenceValue {
    private final String sequenceId;
    private final long version;
    private final String[] itemIds;
    private final String[] industryIds;
    private final long[] timestamps;
    private final String[] eventTypes;
    private final double[] values;

    public SequenceBlock(String sequenceId, long version, List<SequenceEvent> events) {
        this.sequenceId = Objects.requireNonNull(sequenceId, "sequenceId");
        this.version = version;
        this.itemIds = new String[events.size()];
        this.industryIds = new String[events.size()];
        this.timestamps = new long[events.size()];
        this.eventTypes = new String[events.size()];
        this.values = new double[events.size()];
        for (int i = 0; i < events.size(); i++) {
            SequenceEvent event = events.get(i);
            itemIds[i] = event.itemId();
            industryIds[i] = event.industryId();
            timestamps[i] = event.timestamp();
            eventTypes[i] = event.eventType();
            values[i] = event.value();
        }
    }

    public String sequenceId() { return sequenceId; }
    public long version() { return version; }
    public String handleKey() { return sequenceId + "@" + version; }
    @Override public int size() { return itemIds.length; }
    @Override public SequenceBlock baseBlock() { return this; }
    @Override public int baseIndexAt(int logicalIndex) { return logicalIndex; }

    public SequenceEvent eventAtBaseIndex(int index) {
        if (index < 0 || index >= size()) throw new IndexOutOfBoundsException(index);
        return new SequenceEvent(itemIds[index], industryIds[index], timestamps[index], eventTypes[index], values[index]);
    }

    public String industryAtBaseIndex(int index) { return industryIds[index]; }
    public long timestampAtBaseIndex(int index) { return timestamps[index]; }
}
