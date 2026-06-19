package com.nomad.client;

import org.json.JSONObject;

/**
 * Nomad Network 规范（端口映射）。
 */
public class NetworkSpec {

    private String mode = "";
    private final java.util.List<PortSpec> dynamicPorts = new java.util.ArrayList<>();
    private final java.util.List<PortSpec> reservedPorts = new java.util.ArrayList<>();

    private NetworkSpec() {}

    public static NetworkSpec create() {
        return new NetworkSpec();
    }

    public NetworkSpec mode(String mode) {
        this.mode = mode;
        return this;
    }

    /**
     * 添加动态端口（Nomad 自动分配）。
     *
     * @param label 端口标签
     * @param to    映射到容器的端口
     */
    public NetworkSpec addDynamicPort(String label, int to) {
        dynamicPorts.add(new PortSpec(label, 0, to));
        return this;
    }

    /**
     * 添加静态端口（指定固定端口）。
     *
     * @param label 端口标签
     * @param value 主机端口
     * @param to    映射到容器的端口
     */
    public NetworkSpec addReservedPort(String label, int value, int to) {
        reservedPorts.add(new PortSpec(label, value, to));
        return this;
    }

    JSONObject toJson() {
        JSONObject obj = new JSONObject();
        obj.put("Mode", mode);

        if (!dynamicPorts.isEmpty()) {
            org.json.JSONArray arr = new org.json.JSONArray();
            for (PortSpec p : dynamicPorts) arr.put(p.toJson());
            obj.put("DynamicPorts", arr);
        }

        if (!reservedPorts.isEmpty()) {
            org.json.JSONArray arr = new org.json.JSONArray();
            for (PortSpec p : reservedPorts) arr.put(p.toJson());
            obj.put("ReservedPorts", arr);
        }

        return obj;
    }

    private static class PortSpec {
        final String label;
        final int value;
        final int to;

        PortSpec(String label, int value, int to) {
            this.label = label;
            this.value = value;
            this.to = to;
        }

        JSONObject toJson() {
            JSONObject obj = new JSONObject();
            obj.put("Label", label);
            obj.put("Value", value);
            obj.put("To", to);
            return obj;
        }
    }
}
