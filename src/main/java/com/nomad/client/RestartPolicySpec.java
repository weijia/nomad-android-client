package com.nomad.client;

import org.json.JSONObject;

/**
 * Nomad Restart Policy 规范。
 */
public class RestartPolicySpec {

    private final String mode;     // "fail", "delay", "no"
    private final int attempts;
    private final int interval;   // 纳秒
    private final int delay;      // 纳秒

    private RestartPolicySpec(String mode, int attempts, int intervalSeconds, int delaySeconds) {
        this.mode = mode;
        this.attempts = attempts;
        this.interval = intervalSeconds * 1_000_000_000;
        this.delay = delaySeconds * 1_000_000_000;
    }

    public static RestartPolicySpec delay(int attempts, int intervalSeconds, int delaySeconds) {
        return new RestartPolicySpec("delay", attempts, intervalSeconds, delaySeconds);
    }

    public static RestartPolicySpec fail(int attempts, int intervalSeconds, int delaySeconds) {
        return new RestartPolicySpec("fail", attempts, intervalSeconds, delaySeconds);
    }

    public static RestartPolicySpec no() {
        return new RestartPolicySpec("no", 0, 0, 0);
    }

    JSONObject toJson() {
        JSONObject obj = new JSONObject();
        obj.put("Mode", mode);
        obj.put("Attempts", attempts);
        obj.put("Interval", interval);
        obj.put("Delay", delay);
        return obj;
    }
}
