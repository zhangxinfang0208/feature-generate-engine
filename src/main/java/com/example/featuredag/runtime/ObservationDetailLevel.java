package com.example.featuredag.runtime;

/** 单次观测快照的明细级别；级别越高，请求结束时构造快照的成本越高。 */
public enum ObservationDetailLevel {
    /** 仅保留请求耗时、状态、输入规模和计划规模。 */
    BASIC,
    /** 在 BASIC 基础上增加按缓存类型汇总的命中统计。 */
    CACHE,
    /** 在 CACHE 基础上增加逐物理节点执行快照。 */
    NODE;

    public boolean includesCache() {
        return this == CACHE || this == NODE;
    }

    public boolean includesNodes() {
        return this == NODE;
    }
}
