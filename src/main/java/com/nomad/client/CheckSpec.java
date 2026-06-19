package com.nomad.client;

import org.json.JSONObject;

/**
 * Nomad Service Check 规范。
 */
class CheckSpec {

    private final String name;
    private final String type;       // "tcp", "http", "script", "grpc"
    private final int interval;      // 纳秒
    private final int timeout;       // 纳秒
    private String path;
    private String protocol;
    private int port;
    private String command;
    private String[] args;

    CheckSpec(String name, String type, int intervalSeconds, int timeoutSeconds) {
        this.name = name;
        this.type = type;
        this.interval = intervalSeconds * 1_000_000_000;
        this.timeout = timeoutSeconds * 1_000_000_000;
    }

    CheckSpec path(String path) {
        this.path = path;
        return this;
    }

    CheckSpec command(String command) {
        this.command = command;
        return this;
    }

    CheckSpec args(String... args) {
        this.args = args;
        return this;
    }

    CheckSpec port(int port) {
        this.port = port;
        return this;
    }

    CheckSpec protocol(String protocol) {
        this.protocol = protocol;
        return this;
    }

    JSONObject toJson() {
        JSONObject obj = new JSONObject();
        obj.put("Name", name);
        obj.put("Type", type);
        obj.put("Interval", interval);
        obj.put("Timeout", timeout);
        if (path != null) obj.put("Path", path);
        if (command != null) obj.put("Command", command);
        if (args != null) {
            org.json.JSONArray arr = new org.json.JSONArray();
            for (String a : args) arr.put(a);
            obj.put("Args", arr);
        }
        return obj;
    }
}
