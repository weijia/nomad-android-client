package com.nomad.client;

import org.json.JSONObject;

/**
 * Job 注册 / 删除操作的返回结果。
 */
public class RegisterResult {

    private final String evalId;
    private final long jobModifyIndex;
    private final String warnings;
    private final long index;

    private RegisterResult(String evalId, long jobModifyIndex, String warnings, long index) {
        this.evalId = evalId;
        this.jobModifyIndex = jobModifyIndex;
        this.warnings = warnings;
        this.index = index;
    }

    static RegisterResult fromJson(JSONObject json) {
        return new RegisterResult(
                json.optString("EvalID", ""),
                json.optLong("JobModifyIndex", 0),
                json.optString("Warnings", ""),
                json.optLong("Index", 0)
        );
    }

    public String getEvalId() { return evalId; }
    public long getJobModifyIndex() { return jobModifyIndex; }
    public String getWarnings() { return warnings; }
    public long getIndex() { return index; }

    public boolean isSuccess() {
        return warnings == null || warnings.isEmpty();
    }

    @Override
    public String toString() {
        return "RegisterResult{" +
                "evalId='" + evalId + '\'' +
                ", jobModifyIndex=" + jobModifyIndex +
                ", warnings='" + warnings + '\'' +
                ", index=" + index +
                '}';
    }
}
