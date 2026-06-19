package com.nomad.client;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Nomad HTTP API 客户端。
 * <p>
 * 零外部依赖，仅使用 Android SDK 自带的 java.net 和 org.json。
 * 所有网络调用均为阻塞式，在 Android 中请放在子线程中执行。
 * <p>
 * 用法示例：
 * <pre>
 *   NomadClient client = new NomadClient("http://10.0.1.100:4646");
 *   RegisterResult result = client.registerJob(jobJson);
 * </pre>
 */
public class NomadClient {

    private final String baseUrl;
    private final String nomadToken;
    private int connectTimeoutMs = (int) TimeUnit.SECONDS.toMillis(10);
    private int readTimeoutMs    = (int) TimeUnit.SECONDS.toMillis(30);

    /**
     * 创建 NomadClient 实例。
     *
     * @param baseUrl Nomad Agent 地址，例如 "http://10.0.1.100:4646"
     */
    public NomadClient(String baseUrl) {
        this(baseUrl, null);
    }

    /**
     * 创建带 ACL Token 的 NomadClient 实例。
     *
     * @param baseUrl    Nomad Agent 地址
     * @param nomadToken Nomad ACL Token（可为 null）
     */
    public NomadClient(String baseUrl, String nomadToken) {
        if (baseUrl == null || baseUrl.isEmpty()) {
            throw new IllegalArgumentException("baseUrl 不能为空");
        }
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.nomadToken = nomadToken;
    }

    // ---- 自动发现工厂方法 ----

    /**
     * 通过自动发现创建 NomadClient。
     * <p>
     * 在子线程中启动 DiscoveryManager，等待发现 Nomad 服务器后自动创建客户端。
     * <p>
     * 用法：
     * <pre>
     *   // 阻塞等待发现（在子线程中调用）
     *   NomadClient client = NomadClient.discover(context, "my-device", 30_000);
     *   if (client != null) {
     *       client.registerJob(myJob);
     *   }
     * </pre>
     *
     * @param context      Android Context
     * @param instanceName 唯一实例名（通常为 hostname）
     * @param timeoutMs    最大等待时间（毫秒）
     * @return 发现服务器后创建的 NomadClient，超时返回 null
     */
    public static NomadClient discover(android.content.Context context,
                                         String instanceName,
                                         long timeoutMs) {
        return discover(context, instanceName, timeoutMs, null);
    }

    /**
     * 通过自动发现创建带 ACL Token 的 NomadClient。
     *
     * @param context      Android Context
     * @param instanceName 唯一实例名
     * @param timeoutMs    最大等待时间（毫秒）
     * @param nomadToken   Nomad ACL Token（可为 null）
     * @return 发现服务器后创建的 NomadClient，超时返回 null
     */
    public static NomadClient discover(android.content.Context context,
                                         String instanceName,
                                         long timeoutMs,
                                         String nomadToken) {
        DiscoveryManager discovery = DiscoveryManager.create(context, instanceName);
        try {
            discovery.start();
            String serverUrl = discovery.waitForServer(timeoutMs);
            if (serverUrl != null) {
                return new NomadClient(serverUrl, nomadToken);
            }
        } finally {
            discovery.stop();
        }
        return null;
    }

    /**
     * 通过自动发现创建 NomadClient（异步回调版本）。
     * <p>
     * 用法：
     * <pre>
     *   NomadClient.discoverAsync(context, "my-device", 30_000, new DiscoveryCallback() {
     *       public void onDiscovered(NomadClient client) {
     *           // 在子线程中使用 client
     *           client.registerJob(myJob);
     *       }
     *       public void onTimeout() {
     *           Log.w("Nomad", "未发现 Nomad 服务器");
     *       }
     *   });
     * </pre>
     *
     * @param context      Android Context
     * @param instanceName 唯一实例名
     * @param timeoutMs    最大等待时间（毫秒）
     * @param callback     回调接口
     */
    public static void discoverAsync(android.content.Context context,
                                      String instanceName,
                                      long timeoutMs,
                                      DiscoveryCallback callback) {
        discoverAsync(context, instanceName, timeoutMs, null, callback);
    }

    /**
     * 通过自动发现创建 NomadClient（异步回调版本，带 Token）。
     */
    public static void discoverAsync(android.content.Context context,
                                      String instanceName,
                                      long timeoutMs,
                                      String nomadToken,
                                      DiscoveryCallback callback) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            DiscoveryManager discovery = DiscoveryManager.create(context, instanceName);
            try {
                discovery.start();
                String serverUrl = discovery.waitForServer(timeoutMs);
                if (serverUrl != null) {
                    callback.onDiscovered(new NomadClient(serverUrl, nomadToken));
                } else {
                    callback.onTimeout();
                }
            } catch (Exception e) {
                callback.onError(e);
            } finally {
                discovery.stop();
                executor.shutdown();
            }
        });
    }

    // ---- 超时配置 ----

    /**
     * 设置连接超时（毫秒），默认 10 秒。
     */
    public NomadClient setConnectTimeoutMs(int ms) {
        this.connectTimeoutMs = ms;
        return this;
    }

    /**
     * 设置读取超时（毫秒），默认 30 秒。
     */
    public NomadClient setReadTimeoutMs(int ms) {
        this.readTimeoutMs = ms;
        return this;
    }

    // ---- 核心方法 ----

    /**
     * 注册一个 Job 到 Nomad。
     * <p>
     * 对应 Nomad HTTP API: POST /v1/jobs
     *
     * @param jobJson 完整的 Job JSON 对象（需包含 "Job" 键）
     * @return 注册结果
     * @throws NomadException 请求失败时抛出
     */
    public RegisterResult registerJob(JSONObject jobJson) throws NomadException {
        JSONObject response = doPost("/v1/jobs", jobJson);
        return RegisterResult.fromJson(response);
    }

    /**
     * 通过 Builder 构建的 JobSpec 注册 Job。
     *
     * @param jobSpec Job 规范
     * @return 注册结果
     * @throws NomadException 请求失败时抛出
     */
    public RegisterResult registerJob(JobSpec jobSpec) throws NomadException {
        return registerJob(jobSpec.toJson());
    }

    /**
     * 删除一个 Job。
     * <p>
     * 对应 Nomad HTTP API: DELETE /v1/job/:job_id
     *
     * @param jobId Job ID
     * @return 删除结果
     * @throws NomadException 请求失败时抛出
     */
    public RegisterResult deleteJob(String jobId) throws NomadException {
        JSONObject response = doDelete("/v1/job/" + jobId);
        return RegisterResult.fromJson(response);
    }

    /**
     * 获取 Job 列表。
     * <p>
     * 对应 Nomad HTTP API: GET /v1/jobs
     *
     * @return Job 列表 JSON 数组
     * @throws NomadException 请求失败时抛出
     */
    public JSONArray listJobs() throws NomadException {
        return doGetArray("/v1/jobs");
    }

    /**
     * 获取单个 Job 详情。
     * <p>
     * 对应 Nomad HTTP API: GET /v1/job/:job_id
     *
     * @param jobId Job ID
     * @return Job 详情 JSON 对象
     * @throws NomadException 请求失败时抛出
     */
    public JSONObject getJob(String jobId) throws NomadException {
        return doGet("/v1/job/" + jobId);
    }

    // ---- HTTP 底层 ----

    private JSONObject doPost(String path, JSONObject body) throws NomadException {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(baseUrl + path);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            applyToken(conn);
            conn.setConnectTimeout(connectTimeoutMs);
            conn.setReadTimeout(readTimeoutMs);
            conn.setDoOutput(true);

            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(bytes);
            }

            int code = conn.getResponseCode();
            String responseBody = readStream(conn);

            if (code >= 200 && code < 300) {
                return new JSONObject(responseBody);
            } else {
                throw new NomadException("POST " + path + " 失败, HTTP " + code + ": " + responseBody, code);
            }
        } catch (NomadException e) {
            throw e;
        } catch (JSONException e) {
            throw new NomadException("JSON 解析失败: " + e.getMessage(), -1, e);
        } catch (Exception e) {
            throw new NomadException("网络请求失败: " + e.getMessage(), -1, e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private JSONObject doDelete(String path) throws NomadException {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(baseUrl + path);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("DELETE");
            applyToken(conn);
            conn.setConnectTimeout(connectTimeoutMs);
            conn.setReadTimeout(readTimeoutMs);

            int code = conn.getResponseCode();
            String responseBody = readStream(conn);

            if (code >= 200 && code < 300) {
                return new JSONObject(responseBody);
            } else {
                throw new NomadException("DELETE " + path + " 失败, HTTP " + code + ": " + responseBody, code);
            }
        } catch (NomadException e) {
            throw e;
        } catch (JSONException e) {
            throw new NomadException("JSON 解析失败: " + e.getMessage(), -1, e);
        } catch (Exception e) {
            throw new NomadException("网络请求失败: " + e.getMessage(), -1, e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private JSONObject doGet(String path) throws NomadException {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(baseUrl + path);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            applyToken(conn);
            conn.setConnectTimeout(connectTimeoutMs);
            conn.setReadTimeout(readTimeoutMs);

            int code = conn.getResponseCode();
            String responseBody = readStream(conn);

            if (code >= 200 && code < 300) {
                return new JSONObject(responseBody);
            } else {
                throw new NomadException("GET " + path + " 失败, HTTP " + code + ": " + responseBody, code);
            }
        } catch (NomadException e) {
            throw e;
        } catch (JSONException e) {
            throw new NomadException("JSON 解析失败: " + e.getMessage(), -1, e);
        } catch (Exception e) {
            throw new NomadException("网络请求失败: " + e.getMessage(), -1, e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private JSONArray doGetArray(String path) throws NomadException {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(baseUrl + path);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            applyToken(conn);
            conn.setConnectTimeout(connectTimeoutMs);
            conn.setReadTimeout(readTimeoutMs);

            int code = conn.getResponseCode();
            String responseBody = readStream(conn);

            if (code >= 200 && code < 300) {
                return new JSONArray(responseBody);
            } else {
                throw new NomadException("GET " + path + " 失败, HTTP " + code + ": " + responseBody, code);
            }
        } catch (NomadException e) {
            throw e;
        } catch (JSONException e) {
            throw new NomadException("JSON 解析失败: " + e.getMessage(), -1, e);
        } catch (Exception e) {
            throw new NomadException("网络请求失败: " + e.getMessage(), -1, e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void applyToken(HttpURLConnection conn) {
        if (nomadToken != null && !nomadToken.isEmpty()) {
            conn.setRequestProperty("X-Nomad-Token", nomadToken);
        }
    }

    private static String readStream(HttpURLConnection conn) throws Exception {
        InputStream is;
        try {
            is = conn.getInputStream();
        } catch (Exception e) {
            is = conn.getErrorStream();
        }
        if (is == null) return "";

        BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        reader.close();
        is.close();
        return sb.toString();
    }
}
