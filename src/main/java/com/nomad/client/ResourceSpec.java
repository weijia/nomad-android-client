package com.nomad.client;

import org.json.JSONObject;

/**
 * Nomad Resource 规范（CPU / 内存）。
 */
class ResourceSpec {

    private final int cpu;
    private final int memoryMB;

    ResourceSpec(int memoryMB, int cpu) {
        this.memoryMB = memoryMB;
        this.cpu = cpu;
    }

    JSONObject toJson() {
        JSONObject obj = new JSONObject();
        obj.put("CPU", cpu);
        obj.put("MemoryMB", memoryMB);
        return obj;
    }
}
