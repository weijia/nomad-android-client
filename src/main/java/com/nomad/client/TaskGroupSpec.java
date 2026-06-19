package com.nomad.client;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Nomad Task Group 规范。
 */
public class TaskGroupSpec {

    private String name;
    private int count = 1;
    private List<TaskSpec> tasks = new ArrayList<>();
    private List<ServiceSpec> services = new ArrayList<>();
    private Map<String, String> meta = new HashMap<>();
    private NetworkSpec network;
    private RestartPolicySpec restartPolicy;

    private TaskGroupSpec() {}

    public static TaskGroupSpec create(String name) {
        TaskGroupSpec spec = new TaskGroupSpec();
        spec.name = name;
        return spec;
    }

    public TaskGroupSpec count(int count) {
        this.count = count;
        return this;
    }

    public TaskGroupSpec addTask(TaskSpec task) {
        this.tasks.add(task);
        return this;
    }

    public TaskGroupSpec addService(ServiceSpec service) {
        this.services.add(service);
        return this;
    }

    public TaskGroupSpec meta(String key, String value) {
        this.meta.put(key, value);
        return this;
    }

    public TaskGroupSpec network(NetworkSpec network) {
        this.network = network;
        return this;
    }

    public TaskGroupSpec restartPolicy(RestartPolicySpec policy) {
        this.restartPolicy = policy;
        return this;
    }

    JSONObject toJson() {
        JSONObject obj = new JSONObject();
        obj.put("Name", name);
        obj.put("Count", count);

        JSONArray tasksArr = new JSONArray();
        for (TaskSpec t : tasks) tasksArr.put(t.toJson());
        obj.put("Tasks", tasksArr);

        if (!services.isEmpty()) {
            JSONArray svcArr = new JSONArray();
            for (ServiceSpec s : services) svcArr.put(s.toJson());
            obj.put("Services", svcArr);
        }

        if (!meta.isEmpty()) {
            JSONObject metaObj = new JSONObject();
            for (Map.Entry<String, String> e : meta.entrySet()) {
                metaObj.put(e.getKey(), e.getValue());
            }
            obj.put("Meta", metaObj);
        }

        if (network != null) {
            JSONArray networks = new JSONArray();
            networks.put(network.toJson());
            obj.put("Networks", networks);
        }

        if (restartPolicy != null) {
            obj.put("RestartPolicy", restartPolicy.toJson());
        }

        return obj;
    }
}
