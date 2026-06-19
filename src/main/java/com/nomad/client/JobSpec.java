package com.nomad.client;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Nomad Job 规范的 Builder。
 * <p>
 * 提供链式 API 快速构建 Nomad Job JSON。
 * <p>
 * 典型用法 —— 注册一个本地服务到 Nomad：
 * <pre>
 *   JobSpec job = JobSpec.create("my-web-service")
 *       .datacenter("dc1")
 *       .addGroup(
 *           TaskGroupSpec.create("web")
 *               .count(1)
 *               .addTask(
 *                   TaskSpec.create("nginx", "docker")
 *                       .config("image", "nginx:latest")
 *                       .addService(
 *                           ServiceSpec.create("nginx-web")
 *                               .portLabel("http")
 *                               .addTag("web")
 *                               .addCheck("alive", "tcp", 10, 2)
 *                       )
 *                       .resources(256, 500)
 *                       .addNetwork()
 *                           .addDynamicPort("http", 80)
 *                           .build()
 *               )
 *       );
 *
 *   NomadClient client = new NomadClient("http://nomad-server:4646");
 *   RegisterResult result = client.registerJob(job);
 * </pre>
 */
public class JobSpec {

    private String id;
    private String name;
    private String type = "service";
    private int priority = 50;
    private String region;
    private String namespace;
    private List<String> datacenters = new ArrayList<>();
    private List<TaskGroupSpec> taskGroups = new ArrayList<>();
    private Map<String, String> meta = new HashMap<>();

    private JobSpec() {}

    /**
     * 创建一个 JobSpec。
     *
     * @param jobId Job ID（全局唯一标识）
     */
    public static JobSpec create(String jobId) {
        JobSpec spec = new JobSpec();
        spec.id = jobId;
        spec.name = jobId;
        return spec;
    }

    public JobSpec name(String name) {
        this.name = name;
        return this;
    }

    public JobSpec type(String type) {
        this.type = type;
        return this;
    }

    public JobSpec priority(int priority) {
        this.priority = priority;
        return this;
    }

    public JobSpec region(String region) {
        this.region = region;
        return this;
    }

    public JobSpec namespace(String namespace) {
        this.namespace = namespace;
        return this;
    }

    public JobSpec datacenter(String dc) {
        this.datacenters.add(dc);
        return this;
    }

    public JobSpec meta(String key, String value) {
        this.meta.put(key, value);
        return this;
    }

    public JobSpec addGroup(TaskGroupSpec group) {
        this.taskGroups.add(group);
        return this;
    }

    /**
     * 构建为 Nomad API 要求的 JSON 格式（外层包裹 "Job" 键）。
     */
    public JSONObject toJson() {
        JSONObject job = new JSONObject();

        if (id != null) job.put("ID", id);
        if (name != null) job.put("Name", name);
        if (type != null) job.put("Type", type);
        job.put("Priority", priority);
        if (region != null) job.put("Region", region);
        if (namespace != null) job.put("Namespace", namespace);

        if (!datacenters.isEmpty()) {
            JSONArray dcs = new JSONArray();
            for (String dc : datacenters) dcs.put(dc);
            job.put("Datacenters", dcs);
        }

        if (!meta.isEmpty()) {
            JSONObject metaObj = new JSONObject();
            for (Map.Entry<String, String> e : meta.entrySet()) {
                metaObj.put(e.getKey(), e.getValue());
            }
            job.put("Meta", metaObj);
        }

        JSONArray groups = new JSONArray();
        for (TaskGroupSpec g : taskGroups) {
            groups.put(g.toJson());
        }
        job.put("TaskGroups", groups);

        JSONObject wrapper = new JSONObject();
        wrapper.put("Job", job);
        return wrapper;
    }

    @Override
    public String toString() {
        return toJson().toString();
    }
}
