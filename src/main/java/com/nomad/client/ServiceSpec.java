package com.nomad.client;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Nomad Service 规范。
 * <p>
 * 用于在 Nomad 中注册服务发现条目。
 */
public class ServiceSpec {

    private String id;
    private String name;
    private List<String> tags = new ArrayList<>();
    private String portLabel;
    private String addressMode;
    private List<CheckSpec> checks = new ArrayList<>();
    private Map<String, String> meta = new HashMap<>();
    private String provider;

    private ServiceSpec() {}

    public static ServiceSpec create(String name) {
        ServiceSpec spec = new ServiceSpec();
        spec.name = name;
        return spec;
    }

    public ServiceSpec id(String id) {
        this.id = id;
        return this;
    }

    public ServiceSpec portLabel(String portLabel) {
        this.portLabel = portLabel;
        return this;
    }

    public ServiceSpec addressMode(String mode) {
        this.addressMode = mode;
        return this;
    }

    public ServiceSpec addTag(String tag) {
        this.tags.add(tag);
        return this;
    }

    public ServiceSpec addCheck(String name, String type, int intervalSeconds, int timeoutSeconds) {
        this.checks.add(new CheckSpec(name, type, intervalSeconds, timeoutSeconds));
        return this;
    }

    public ServiceSpec meta(String key, String value) {
        this.meta.put(key, value);
        return this;
    }

    public ServiceSpec provider(String provider) {
        this.provider = provider;
        return this;
    }

    JSONObject toJson() {
        JSONObject obj = new JSONObject();
        if (id != null) obj.put("Id", id);
        obj.put("Name", name);

        if (!tags.isEmpty()) {
            JSONArray tagsArr = new JSONArray();
            for (String t : tags) tagsArr.put(t);
            obj.put("Tags", tagsArr);
        }

        if (portLabel != null) obj.put("PortLabel", portLabel);
        if (addressMode != null) obj.put("AddressMode", addressMode);
        if (provider != null) obj.put("Provider", provider);

        if (!checks.isEmpty()) {
            JSONArray checksArr = new JSONArray();
            for (CheckSpec c : checks) checksArr.put(c.toJson());
            obj.put("Checks", checksArr);
        }

        if (!meta.isEmpty()) {
            JSONObject metaObj = new JSONObject();
            for (Map.Entry<String, String> e : meta.entrySet()) {
                metaObj.put(e.getKey(), e.getValue());
            }
            obj.put("Meta", metaObj);
        }

        return obj;
    }
}
