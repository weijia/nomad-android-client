package com.nomad.client.example;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.nomad.client.*;

import org.json.JSONArray;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Android 中使用 NomadClient 的完整示例。
 * <p>
 * 演示如何从 Android 设备将本地服务注册到 Nomad 集群，
 * 以及如何使用自动发现功能找到局域网中的 Nomad 服务器。
 */
public class NomadExampleActivity extends AppCompatActivity {

    private static final String TAG = "NomadExample";

    // Nomad Agent 地址（替换为你的实际地址）
    private static final String NOMAD_ADDR = "http://10.0.1.100:4646";

    // 如果 Nomad 启用了 ACL，填入 Token
    private static final String NOMAD_TOKEN = null;

    private NomadClient client;
    private DiscoveryManager discoveryManager;
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // ========== 方式 1：直接连接（已知服务器地址） ==========
        client = new NomadClient(NOMAD_ADDR, NOMAD_TOKEN);
        registerMyService();

        // ========== 方式 2：自动发现服务器 ==========
        discoverAndRegister();
    }

    // ================================================================
    //  自动发现示例
    // ================================================================

    /**
     * 示例 5：使用自动发现找到 Nomad 服务器，然后注册服务。
     * <p>
     * 这是最推荐的方式 —— 不需要硬编码 Nomad 服务器地址。
     */
    private void discoverAndRegister() {
        // 方式 A：异步回调（推荐）
        NomadClient.discoverAsync(this, android.os.Build.HOST, 30_000,
                new DiscoveryCallback() {
                    @Override
                    public void onDiscovered(NomadClient discoveredClient) {
                        Log.d(TAG, "自动发现 Nomad 服务器: " + discoveredClient);
                        // 发现后立即注册服务
                        try {
                            RegisterResult result = discoveredClient.registerJob(buildMyJob());
                            mainHandler.post(() ->
                                    Toast.makeText(NomadExampleActivity.this,
                                            "自动发现并注册成功!", Toast.LENGTH_SHORT).show()
                            );
                        } catch (NomadException e) {
                            Log.e(TAG, "注册失败", e);
                        }
                    }

                    @Override
                    public void onTimeout() {
                        mainHandler.post(() ->
                                Toast.makeText(NomadExampleActivity.this,
                                        "未发现 Nomad 服务器", Toast.LENGTH_LONG).show()
                        );
                    }

                    @Override
                    public void onError(Exception e) {
                        Log.e(TAG, "发现过程出错", e);
                    }
                });
    }

    /**
     * 示例 6：手动管理 DiscoveryManager（更灵活的控制）。
     * <p>
     * 适用于需要持续监听节点上下线场景。
     */
    private void startContinuousDiscovery() {
        discoveryManager = DiscoveryManager.create(this, android.os.Build.HOST);

        discoveryManager.addListener(new DiscoveryListener() {
            @Override
            public void onNodeDiscovered(DiscoveredNode node) {
                Log.d(TAG, "发现 Nomad 节点: " + node);
                Log.d(TAG, "  HTTP: " + node.getHttpAddress());
                Log.d(TAG, "  RPC:  " + node.getRpcAddress());
                Log.d(TAG, "  Serf: " + node.getJoinAddress());
            }

            @Override
            public void onNodeLost(DiscoveredNode node) {
                Log.d(TAG, "Nomad 节点丢失: " + node.getInstanceName());
            }
        });

        discoveryManager.start();

        // 定期检查发现的节点
        ioExecutor.execute(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(15_000);

                    // 获取所有发现的节点
                    Map<String, DiscoveredNode> nodes = discoveryManager.getDiscoveredNodes();
                    String[] httpAddrs = discoveryManager.getHttpAddresses();
                    String[] joinAddrs = discoveryManager.getJoinAddresses();

                    Log.d(TAG, "当前发现 " + nodes.size() + " 个节点");
                    for (String addr : httpAddrs) {
                        Log.d(TAG, "  -> " + addr);
                    }

                    // 自动连接到第一个可用节点
                    if (!httpAddrs.isEmpty() && client == null) {
                        NomadClient autoClient = new NomadClient(httpAddrs[0], NOMAD_TOKEN);
                        // 使用 autoClient 进行操作...
                    }
                } catch (InterruptedException e) {
                    break;
                }
            }
        });
    }

    /**
     * 示例 7：仅使用 UDP 广播发现（不使用 mDNS）。
     */
    private void broadcastOnlyDiscovery() {
        ioExecutor.execute(() -> {
            BroadcastDiscovery broadcast = new BroadcastDiscovery(android.os.Build.HOST);

            broadcast.addListener(new DiscoveryListener() {
                @Override
                public void onNodeDiscovered(DiscoveredNode node) {
                    Log.d(TAG, "广播发现: " + node.getHttpAddress());
                }

                @Override
                public void onNodeLost(DiscoveredNode node) {
                    Log.d(TAG, "广播节点丢失: " + node.getInstanceName());
                }
            });

            broadcast.start();

            // 等待 30 秒收集节点
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException e) {
                // ignore
            }

            // 获取发现的节点
            String[] servers = broadcast.getHttpAddresses();
            Log.d(TAG, "广播发现 " + servers.length + " 个节点");

            broadcast.stop();
        });
    }

    // ================================================================
    //  Job 注册示例
    // ================================================================

    /**
     * 示例 1：注册一个 Docker 服务到 Nomad
     */
    private void registerMyService() {
        ioExecutor.execute(() -> {
            try {
                RegisterResult result = client.registerJob(buildMyJob());

                mainHandler.post(() -> {
                    if (result.isSuccess()) {
                        Toast.makeText(this,
                                "服务注册成功! JobModifyIndex=" + result.getJobModifyIndex(),
                                Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this,
                                "注册有警告: " + result.getWarnings(),
                                Toast.LENGTH_LONG).show();
                    }
                });

            } catch (NomadException e) {
                Log.e(TAG, "注册失败", e);
                mainHandler.post(() ->
                        Toast.makeText(this, "注册失败: " + e.getMessage(), Toast.LENGTH_LONG).show()
                );
            }
        });
    }

    /**
     * 构建 Job 规范（提取为公共方法，方便复用）。
     */
    private JobSpec buildMyJob() {
        return JobSpec.create("my-web-app")
                .datacenter("dc1")
                .type("service")
                .meta("owner", "mobile-team")
                .addGroup(
                        TaskGroupSpec.create("web-group")
                                .count(1)
                                .restartPolicy(RestartPolicySpec.delay(3, 300, 15))
                                .addTask(
                                        TaskSpec.create("web", "docker")
                                                .config("image", "nginx:latest")
                                                .config("ports", "http")
                                                .env("ENV", "production")
                                                .resources(256, 500)
                                                .addService(
                                                        ServiceSpec.create("web-service")
                                                                .portLabel("http")
                                                                .addTag("web")
                                                                .addTag("v1")
                                                                .addCheck("alive", "tcp", 10, 2)
                                                )
                                                .network(
                                                        NetworkSpec.create()
                                                                .addDynamicPort("http", 80)
                                                )
                                )
                );
    }

    /**
     * 示例 2：注册一个本地已有进程（raw/exec 驱动）
     */
    private void registerLocalService() {
        ioExecutor.execute(() -> {
            try {
                JobSpec job = JobSpec.create("android-local-service")
                        .datacenter("dc1")
                        .addGroup(
                                TaskGroupSpec.create("local")
                                        .addTask(
                                                TaskSpec.createRaw("my-app")
                                                        .config("command", "/data/app/my-service.sh")
                                                        .config("args", "start")
                                                        .addService(
                                                                ServiceSpec.create("android-app")
                                                                        .portLabel("api")
                                                                        .addTag("mobile")
                                                                        .addTag("local")
                                                                        .addCheck("health", "tcp", 10, 3)
                                                        )
                                                        .resources(128, 200)
                                                        .network(
                                                                NetworkSpec.create()
                                                                        .addDynamicPort("api", 8080)
                                                        )
                                        )
                        );

                RegisterResult result = client.registerJob(job);
                Log.d(TAG, "本地服务注册结果: " + result);

            } catch (NomadException e) {
                Log.e(TAG, "本地服务注册失败", e);
            }
        });
    }

    /**
     * 示例 3：查询已注册的 Job 列表
     */
    private void listJobs() {
        ioExecutor.execute(() -> {
            try {
                JSONArray jobs = client.listJobs();
                mainHandler.post(() -> {
                    Log.d(TAG, "已注册的 Job 数量: " + jobs.length());
                    for (int i = 0; i < jobs.length(); i++) {
                        Log.d(TAG, "Job[" + i + "]: " + jobs.optJSONObject(i).optString("ID"));
                    }
                });
            } catch (NomadException e) {
                Log.e(TAG, "查询 Job 列表失败", e);
            }
        });
    }

    /**
     * 示例 4：删除一个 Job
     */
    private void stopService(String jobId) {
        ioExecutor.execute(() -> {
            try {
                RegisterResult result = client.deleteJob(jobId);
                mainHandler.post(() ->
                        Toast.makeText(this, "Job 已停止: " + jobId, Toast.LENGTH_SHORT).show()
                );
            } catch (NomadException e) {
                Log.e(TAG, "停止 Job 失败", e);
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ioExecutor.shutdown();
        if (discoveryManager != null) {
            discoveryManager.stop();
        }
    }
}
