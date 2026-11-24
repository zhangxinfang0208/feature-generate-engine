package com.example.featuredag.runtime;

import com.example.featuredag.physical.PhysicalNode;
import com.example.featuredag.physical.PhysicalPlan;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 运行时按序号寻址所需的计划布局（C9）：节点序号、槽位序号表，以及每个节点的输入/输出槽序号。
 * 计划不可变，布局对每个计划只构建一次；核心执行路径只走数组，
 * 不再逐节点以 slot:N / physical:N 为键查哈希表。
 */
final class RuntimePlanLayout {
    private final PhysicalPlan plan;
    private final String[] nodeIds;
    private final String[] slotNames;
    private final Map<String, Integer> nodeOrdinals;
    private final Map<String, Integer> slotOrdinals;
    private final int[] outputSlotByNode;
    private final int[][] inputSlotsByNode;
    private final String[] outputFeatureNames;
    private final int[] outputFeatureSlots;

    private RuntimePlanLayout(PhysicalPlan plan) {
        this.plan = plan;
        List<PhysicalNode> nodes = plan.nodes();
        this.nodeIds = new String[nodes.size()];
        this.nodeOrdinals = new HashMap<String, Integer>();
        Map<String, Integer> slots = new HashMap<String, Integer>();
        this.outputSlotByNode = new int[nodes.size()];
        this.inputSlotsByNode = new int[nodes.size()][];
        for (int index = 0; index < nodes.size(); index++) {
            PhysicalNode node = nodes.get(index);
            nodeIds[index] = node.physicalNodeId();
            nodeOrdinals.put(node.physicalNodeId(), index);
            outputSlotByNode[index] = slotOrdinal(slots, node.outputSlot());
            List<String> inputs = node.inputSlots();
            int[] inputOrdinals = new int[inputs.size()];
            for (int input = 0; input < inputs.size(); input++) {
                inputOrdinals[input] = slotOrdinal(slots, inputs.get(input));
            }
            inputSlotsByNode[index] = inputOrdinals;
        }
        // 输出特征槽位由计划声明；手工构建的计划里它可能不由任何节点产生，同样登记以保证可按序号读取。
        this.outputFeatureNames = new String[plan.outputFeatureSlots().size()];
        this.outputFeatureSlots = new int[outputFeatureNames.length];
        int output = 0;
        for (Map.Entry<String, String> entry : plan.outputFeatureSlots().entrySet()) {
            outputFeatureNames[output] = entry.getKey();
            outputFeatureSlots[output] = slotOrdinal(slots, entry.getValue());
            output++;
        }
        this.slotOrdinals = slots;
        this.slotNames = new String[slots.size()];
        for (Map.Entry<String, Integer> entry : slots.entrySet()) {
            slotNames[entry.getValue()] = entry.getKey();
        }
    }

    static RuntimePlanLayout of(PhysicalPlan plan) {
        return new RuntimePlanLayout(plan);
    }

    private static int slotOrdinal(Map<String, Integer> slots, String slot) {
        Integer existing = slots.get(slot);
        if (existing != null) return existing;
        int ordinal = slots.size();
        slots.put(slot, ordinal);
        return ordinal;
    }

    PhysicalPlan plan() {
        return plan;
    }

    int nodeCount() {
        return nodeIds.length;
    }

    String nodeId(int ordinal) {
        return nodeIds[ordinal];
    }

    /** 节点在计划中的序号；不属于该计划时为 -1。 */
    int nodeOrdinal(String physicalNodeId) {
        Integer ordinal = nodeOrdinals.get(physicalNodeId);
        return ordinal == null ? -1 : ordinal;
    }

    int slotCount() {
        return slotNames.length;
    }

    String slotName(int ordinal) {
        return slotNames[ordinal];
    }

    /** 槽位序号；不属于该计划时为 -1。 */
    int slotOrdinal(String slot) {
        Integer ordinal = slotOrdinals.get(slot);
        return ordinal == null ? -1 : ordinal;
    }

    /** 节点写入的唯一输出槽（C9）。 */
    int outputSlot(int nodeOrdinal) {
        return outputSlotByNode[nodeOrdinal];
    }

    /** 节点各输入槽的序号，顺序与 inputSlots() 一致。 */
    int[] inputSlots(int nodeOrdinal) {
        return inputSlotsByNode[nodeOrdinal];
    }

    String[] outputFeatureNames() {
        return outputFeatureNames;
    }

    int[] outputFeatureSlots() {
        return outputFeatureSlots;
    }
}
