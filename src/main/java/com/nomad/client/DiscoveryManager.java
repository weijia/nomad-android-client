package com.nomad.client;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 统一的 Nomad 服务器自动发现管理器。
 * <p>
 * 整合 UDP 广播发现和 mDNS 发现两种通道，对应 Go 代码 command/agent/mdns_setup.go 中的逻辑。
 * <p>
 * 工作机制：
 * <ul>
 *   <li>UDP 广播（主要）：端口 4649，每 10 秒广播，30 秒超时</li>
 *   <li>mDNS（辅助）：使用 Android NSD API，每 30 秒查询，10 分钟超时</li>
 * </ul>
 * <p>
 * 用法示例：
 * <pre>
 *   // 最简用法 —— 自动发现局域网中的 Nomad 服务器
 *   DiscoveryManager discovery = DiscoveryManager.create(context, "my-device");
 *   discovery.addListener(new DiscoveryListener() {
 *       public void onNodeDiscovered(DiscoveredNode node) {
 *           Log.d("Nomad", "发现节点: " + node.getHttpAddress());
 *       }
 *       public void onNodeLost(DiscoveredNode node) {
 *           Log.d("Nomad", "节点丢失: " + node.getInstanceName());
 *       }
 *   });
 *   discovery.start();
 *
 *   // 等待发现后获取节点
 *   String[] servers = discovery.getHttpAddresses();
 *   if (servers.length > 0) {
 *       NomadClient client = new NomadClient(servers[0]);
 *       client.registerJob(myJob);
 *   }
 * </pre>
 */
public class DiscoveryManager {

    private static final String TAG = "NomadDiscovery";

    /** 延迟引导超时（毫秒）—— 对应 Go 中的 delayedBootstrapTimeout */
    private static final long DISCOVERY_WAIT_TIMEOUT_MS = 30_000;

    /** 发现检查间隔（毫秒）—— 对应 Go 中的 discoveryCheckInterval */
    private static final long DISCOVERY_CHECK_INTERVAL_MS = 5_000;

    private final Context context;
    private final String instanceName;
    private final int httpPort;
    private final int rpcPort;
    private final int serfPort;
    private final String advertiseAddress;

    private BroadcastDiscovery broadcastDiscovery;
    private MdnsDiscovery mdnsDiscovery;

    private final ConcurrentHashMap<String, DiscoveredNode> allNodes = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<DiscoveryListener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean(false);

    private Handler mainHandler;
    private volatile Thread monitorThread;

    private DiscoveryManager(Context context, String instanceName,
                              int httpPort, int rpcPort, int serfPort,
                              String advertiseAddress) {
        this.context = context.getApplicationContext();
        this.instanceName = instanceName;
        this.httpPort = httpPort;
        this.rpcPort = rpcPort;
        this.serfPort = serfPort;
        this.advertiseAddress = advertiseAddress;
    }

    // ---- 工厂方法 ----

    /**
     * 创建 DiscoveryManager（使用默认端口）。
     *
     * @param context      Android Context
     * @param instanceName 唯一实例名（通常为 hostname）
     */
    public static DiscoveryManager create(Context context, String instanceName) {
        return new DiscoveryManager(context, instanceName, 4646, 4647, 4648, null);
    }

    /**
     * 创建 DiscoveryManager（自定义端口）。
     *
     * @param context          Android Context
     * @param instanceName     唯一实例名
     * @param httpPort         HTTP API 端口
     * @param rpcPort          RPC 端口
     * @param serfPort         Serf 端口
     * @param advertiseAddress 广播地址（null 则自动检测）
     */
    public static DiscoveryManager create(Context context, String instanceName,
                                          int httpPort, int rpcPort, int serfPort,
                                          String advertiseAddress) {
        return new DiscoveryManager(context, instanceName, httpPort, rpcPort, serfPort, advertiseAddress);
    }

    // ---- 监听器 ----

    /**
     * 添加节点发现监听器。
     * <p>
     * 回调在主线程执行（如果主 Handler 可用）。
     */
    public void addListener(DiscoveryListener listener) {
        listeners.add(listener);
    }

    public void removeListener(DiscoveryListener listener) {
        listeners.remove(listener);
    }

    // ---- 启动/停止 ----

    /**
     * 启动双通道发现（UDP 广播 + mDNS）。
     * <p>
     * UDP 广播总是启动；mDNS 尝试启动，失败不影响广播。
     */
    public void start() {
        if (running.getAndSet(true)) {
            Log.w(TAG, "发现管理器已在运行");
            return;
        }

        try {
            mainHandler = new Handler(Looper.getMainLooper());
        } catch (Exception e) {
            mainHandler = null;
        }

        // 创建内部监听器，合并两个来源的节点
        DiscoveryListener internalListener = new DiscoveryListener() {
            @Override
            public void onNodeDiscovered(DiscoveredNode node) {
                DiscoveredNode existing = allNodes.putIfAbsent(node.getInstanceName(), node);
                if (existing == null) {
                    notifyNodeDiscovered(node);
                }
            }

            @Override
            public void onNodeLost(DiscoveredNode node) {
                // 只在两个来源都没有该节点时才移除
                if (broadcastDiscovery != null && broadcastDiscovery.getDiscoveredNodes().containsKey(node.getInstanceName())) {
                    return;
                }
                if (mdnsDiscovery != null && mdnsDiscovery.getDiscoveredNodes().containsKey(node.getInstanceName())) {
                    return;
                }
                allNodes.remove(node.getInstanceName());
                notifyNodeLost(node);
            }
        };

        // 1. 总是启动 UDP 广播发现
        broadcastDiscovery = new BroadcastDiscovery(
                instanceName, httpPort, rpcPort, serfPort, advertiseAddress
        );
        broadcastDiscovery.addListener(internalListener);
        broadcastDiscovery.start();
        Log.d(TAG, "UDP 广播发现已启动");

        // 2. 尝试启动 mDNS 发现（失败不影响广播）
        try {
            mdnsDiscovery = new MdnsDiscovery(
                    context, instanceName, httpPort, rpcPort, serfPort, advertiseAddress
            );
            mdnsDiscovery.addListener(internalListener);
            mdnsDiscovery.start();
            Log.d(TAG, "mDNS 发现已启动");
        } catch (Exception e) {
            Log.w(TAG, "mDNS 启动失败，回退到纯广播模式", e);
            mdnsDiscovery = null;
        }

        // 3. 启动监控线程
        monitorThread = new Thread(this::monitorLoop, "nomad-discovery-monitor");
        monitorThread.setDaemon(true);
        monitorThread.start();

        Log.d(TAG, "Nomad 服务器自动发现已启动, 实例=" + instanceName);
    }

    /**
     * 停止所有发现通道。
     */
    public void stop() {
        if (!running.getAndSet(false)) {
            return;
        }

        if (broadcastDiscovery != null) {
            broadcastDiscovery.stop();
        }
        if (mdnsDiscovery != null) {
            mdnsDiscovery.stop();
        }
        if (monitorThread != null) {
            monitorThread.interrupt();
        }

        allNodes.clear();
        Log.d(TAG, "Nomad 服务器自动发现已停止");
    }

    // ---- 查询 ----

    /**
     * @return 所有发现的节点（合并两个来源，去重）
     */
    public Map<String, DiscoveredNode> getDiscoveredNodes() {
        return allNodes;
    }

    /**
     * @return 所有活跃节点的 Serf join 地址列表（去重）
     */
    public String[] getJoinAddresses() {
        Set<String> addrs = new HashSet<>();
        if (broadcastDiscovery != null) {
            for (String addr : broadcastDiscovery.getJoinAddresses()) {
                addrs.add(addr);
            }
        }
        if (mdnsDiscovery != null) {
            for (String addr : mdnsDiscovery.getJoinAddresses()) {
                addrs.add(addr);
            }
        }
        return addrs.toArray(new String[0]);
    }

    /**
     * @return 所有活跃节点的 HTTP API 地址列表（去重）
     */
    public String[] getHttpAddresses() {
        Set<String> addrs = new HashSet<>();
        if (broadcastDiscovery != null) {
            for (String addr : broadcastDiscovery.getHttpAddresses()) {
                addrs.add(addr);
            }
        }
        if (mdnsDiscovery != null) {
            for (String addr : mdnsDiscovery.getHttpAddresses()) {
                addrs.add(addr);
            }
        }
        return addrs.toArray(new String[0]);
    }

    /**
     * 阻塞等待直到发现至少一个节点。
     * <p>
     * 对应 Go 代码中的延迟引导策略（Phase 1）。
     *
     * @param timeoutMs 最大等待时间（毫秒），默认 30 秒
     * @return 发现的节点列表，超时返回空列表
     */
    public List<DiscoveredNode> waitForNodes(long timeoutMs) {
        return waitForNodes(timeoutMs, DISCOVERY_CHECK_INTERVAL_MS);
    }

    /**
     * 阻塞等待直到发现至少一个节点。
     *
     * @param timeoutMs      最大等待时间（毫秒）
     * @param checkIntervalMs 检查间隔（毫秒）
     * @return 发现的节点列表
     */
    public List<DiscoveredNode> waitForNodes(long timeoutMs, long checkIntervalMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;

        while (System.currentTimeMillis() < deadline && running.get()) {
            if (!allNodes.isEmpty()) {
                return new ArrayList<>(allNodes.values());
            }
            try {
                Thread.sleep(checkIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        return new ArrayList<>(allNodes.values());
    }

    /**
     * 阻塞等待直到发现至少一个节点，然后返回其 HTTP API 地址。
     * <p>
     * 这是最常用的便捷方法 —— 自动发现并返回可用的 Nomad 服务器地址。
     *
     * @param timeoutMs 最大等待时间（毫秒）
     * @return 第一个发现的 HTTP API 地址，超时返回 null
     */
    public String waitForServer(long timeoutMs) {
        List<DiscoveredNode> nodes = waitForNodes(timeoutMs);
        if (!nodes.isEmpty()) {
            return nodes.get(0).getHttpAddress();
        }
        return null;
    }

    public boolean isRunning() {
        return running.get();
    }

    public BroadcastDiscovery getBroadcastDiscovery() {
        return broadcastDiscovery;
    }

    public MdnsDiscovery getMdnsDiscovery() {
        return mdnsDiscovery;
    }

    // ---- 内部实现 ----

    /**
     * 监控循环 —— 定期合并两个来源的节点，确保一致性。
     * 对应 Go 代码中的稳态运行阶段（Phase 2）。
     */
    private void monitorLoop() {
        while (running.get()) {
            try {
                Thread.sleep(DISCOVERY_CHECK_INTERVAL_MS);
            } catch (InterruptedException e) {
                break;
            }

            if (!running.get()) break;

            // 合并两个来源的节点
            mergeNodes();
        }
    }

    private void mergeNodes() {
        if (broadcastDiscovery != null) {
            for (DiscoveredNode node : broadcastDiscovery.getDiscoveredNodes().values()) {
                allNodes.putIfAbsent(node.getInstanceName(), node);
            }
        }
        if (mdnsDiscovery != null) {
            for (DiscoveredNode node : mdnsDiscovery.getDiscoveredNodes().values()) {
                allNodes.putIfAbsent(node.getInstanceName(), node);
            }
        }
    }

    private void notifyNodeDiscovered(final DiscoveredNode node) {
        if (mainHandler != null && Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(() -> {
                for (DiscoveryListener l : listeners) {
                    l.onNodeDiscovered(node);
                }
            });
        } else {
            for (DiscoveryListener l : listeners) {
                l.onNodeDiscovered(node);
            }
        }
    }

    private void notifyNodeLost(final DiscoveredNode node) {
        if (mainHandler != null && Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(() -> {
                for (DiscoveryListener l : listeners) {
                    l.onNodeLost(node);
                }
            });
        } else {
            for (DiscoveryListener l : listeners) {
                l.onNodeLost(node);
            }
        }
    }
}
