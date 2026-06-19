package com.nomad.client;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Nomad Task 规范。
 */
public class TaskSpec {

    private String name;
    private String driver = "docker";
    private Map<String, Object> config = new HashMap<>();
    private List<ServiceSpec> services = new ArrayList<>();
    private Map<String, String> env = new HashMap<>();
    private ResourceSpec resources;
    private NetworkSpec network;

    private TaskSpec() {}

    public static TaskSpec create(String name, String driver) {
        TaskSpec spec = new TaskSpec();
        spec.name = name;
        spec.driver = driver;
        return spec;
    }

    /**
     * 快捷方式：创建一个 raw/exec 驱动的 Task（用于注册本地已有服务）。
     */
    public static TaskSpec createRaw(String name) {
        return create(name, "raw/exec");
    }

    public TaskSpec config(String key, Object value) {
        this.config.put(key, value);
        return this;
    }

    public TaskSpec config(Map<String, Object> config) {
        this.config.putAll(config);
        return this;
    }

    public TaskSpec addService(ServiceSpec service) {
        this.services.add(service);
        return this;
    }

    public TaskSpec env(String key, String value) {
        this.env.put(key, value);
        return this;
    }

    /**
     * 设置资源限制。
     *
     * @param memoryMB 内存 (MB)
     * @param cpu      CPU (MHz)
     */
    public TaskSpec resources(int memoryMB, int cpu) {
        this.resources = new ResourceSpec(memoryMB, cpu);
        return this;
    }

    public TaskSpec network(NetworkSpec network) {
        this.network = network;
        return this;
    }

    JSONObject toJson() {
        JSONObject obj = new JSONObject();
        obj.put("Name", name);
        obj.put("Driver", driver);

        if (!config.isEmpty()) {
            JSONObject configObj = new JSONObject();
            for (Map.Entry<String, Object> e : config.entrySet()) {
                configObj.put(e.getKey(), e.getValue());
            }
            obj.put("Config", configObj);
        }

        if (!services.isEmpty()) {
            JSONArray svcArr = new JSONArray();
            for (ServiceSpec s : services) svcArr.put(s.toJson());
            obj.put("Services", svcArr);
        }

        if (!env.isEmpty()) {
            JSONObject envObj = new JSONObject();
            for (Map.Entry<String, String> e : env.entrySet()) {
                envObj.put(e.getKey(), e.getValue());
            }
            obj.put("Env", envObj);
        }

        if (resources != null) {
            obj.put("Resources", resources.toJson());
        }

        if (network != null) {
            JSONArray networks = new JSONArray();
            networks.put(network.toJson());
            obj.put("Networks", networks);
        }

        return obj;
    }
}
