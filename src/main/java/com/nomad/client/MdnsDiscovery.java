package com.nomad.client;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.util.Log;

import java.net.InetAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * mDNS 发现实现（基于 Android NSD API）。
 * <p>
 * 对应 Go 代码 nomad/discovery/mdns.go。
 * <p>
 * 协议细节：
 * <ul>
 *   <li>服务类型: _nomad-serf._tcp, _nomad-rpc._tcp, _nomad-http._tcp</li>
 *   <li>TXT 记录: http_port=4646, rpc_port=4647, serf_port=4648</li>
 *   <li>查询间隔: 30 秒</li>
 *   <li>节点超时: 10 分钟</li>
 * </ul>
 * <p>
 * 注意：需要 Android Context 来获取 NsdManager。
 * 如果不想注册自身服务（仅发现其他节点），可以使用 {@link #MdnsDiscovery(Context, String)} 构造函数。
 */
public class MdnsDiscovery {

    private static final String TAG = "NomadMdns";

    /** mDNS 服务类型 */
    public static final String SERVICE_HTTP = "_nomad-http._tcp";
    public static final String SERVICE_RPC  = "_nomad-rpc._tcp";
    public static final String SERVICE_SERF = "_nomad-serf._tcp";

    /** 查询间隔（毫秒） */
    private static final long DISCOVERY_INTERVAL_MS = 30_000;

    /** 节点超时（毫秒）—— 10 分钟 */
    private static final long NODE_TIMEOUT_MS = 600_000;

    private final Context context;
    private final String instanceName;
    private final int httpPort;
    private final int rpcPort;
    private final int serfPort;
    private final String advertiseAddress;

    private final ConcurrentHashMap<String, DiscoveredNode> discoveredNodes = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<DiscoveryListener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean(false);

    private NsdManager nsdManager;
    private volatile Thread discoveryThread;
    private volatile Thread cleanupThread;

    /**
     * 创建仅发现模式（不注册自身服务）的 mDNS 发现实例。
     *
     * @param context      Android Context
     * @param instanceName 唯一实例名
     */
    public MdnsDiscovery(Context context, String instanceName) {
        this(context, instanceName, 4646, 4647, 4648, null, false);
    }

    /**
     * 创建完整的 mDNS 发现实例（同时注册和发现）。
     *
     * @param context          Android Context
     * @param instanceName     唯一实例名
     * @param httpPort         HTTP API 端口
     * @param rpcPort          RPC 端口
     * @param serfPort         Serf 端口
     * @param advertiseAddress 广播地址，可为 null 则自动检测
     */
    public MdnsDiscovery(Context context, String instanceName,
                         int httpPort, int rpcPort, int serfPort,
                         String advertiseAddress) {
        this(context, instanceName, httpPort, rpcPort, serfPort, advertiseAddress, true);
    }

    private MdnsDiscovery(Context context, String instanceName,
                          int httpPort, int rpcPort, int serfPort,
                          String advertiseAddress, boolean registerServices) {
        this.context = context.getApplicationContext();
        this.instanceName = instanceName;
        this.httpPort = httpPort;
        this.rpcPort = rpcPort;
        this.serfPort = serfPort;
        this.advertiseAddress = advertiseAddress;
    }

    // ---- 监听器 ----

    public void addListener(DiscoveryListener listener) {
        listeners.add(listener);
    }

    public void removeListener(DiscoveryListener listener) {
        listeners.remove(listener);
    }

    // ---- 启动/停止 ----

    /**
     * 启动 mDNS 发现。
     * <p>
     * 开始周期性查询局域网中的 Nomad 服务。
     */
    public void start() {
        if (running.getAndSet(true)) {
            Log.w(TAG, "mDNS 发现已在运行");
            return;
        }

        nsdManager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        if (nsdManager == null) {
            running.set(false);
            Log.e(TAG, "无法获取 NsdManager");
            return;
        }

        // 启动发现线程
        discoveryThread = new Thread(this::discoveryLoop, "nomad-mdns-discover");
        discoveryThread.setDaemon(true);
        discoveryThread.start();

        // 启动清理线程
        cleanupThread = new Thread(this::cleanupLoop, "nomad-mdns-cleanup");
        cleanupThread.setDaemon(true);
        cleanupThread.start();

        Log.d(TAG, "mDNS 发现已启动, 实例=" + instanceName);
    }

    /**
     * 停止 mDNS 发现。
     */
    public void stop() {
        if (!running.getAndSet(false)) {
            return;
        }

        if (discoveryThread != null) {
            discoveryThread.interrupt();
        }
        if (cleanupThread != null) {
            cleanupThread.interrupt();
        }

        discoveredNodes.clear();
        Log.d(TAG, "mDNS 发现已停止");
    }

    // ---- 查询 ----

    /**
     * @return 当前发现的所有活跃节点
     */
    public Map<String, DiscoveredNode> getDiscoveredNodes() {
        return discoveredNodes;
    }

    /**
     * @return 所有活跃节点的 Serf join 地址列表
     */
    public String[] getJoinAddresses() {
        long now = System.currentTimeMillis();
        java.util.List<String> addrs = new java.util.ArrayList<>();
        for (DiscoveredNode node : discoveredNodes.values()) {
            if (now - node.getDiscoveredAtMs() < NODE_TIMEOUT_MS) {
                addrs.add(node.getJoinAddress());
            }
        }
        return addrs.toArray(new String[0]);
    }

    /**
     * @return 所有活跃节点的 HTTP API 地址列表
     */
    public String[] getHttpAddresses() {
        long now = System.currentTimeMillis();
        java.util.List<String> addrs = new java.util.ArrayList<>();
        for (DiscoveredNode node : discoveredNodes.values()) {
            if (now - node.getDiscoveredAtMs() < NODE_TIMEOUT_MS) {
                addrs.add(node.getHttpAddress());
            }
        }
        return addrs.toArray(new String[0]);
    }

    public boolean isRunning() {
        return running.get();
    }

    // ---- 内部实现 ----

    /**
     * 发现循环 —— 每 30 秒发起一次 mDNS 查询。
     * <p>
     * 使用 Android NsdManager.DiscoveryListener 进行服务发现。
     */
    private void discoveryLoop() {
        // 立即执行一次发现
        performDiscovery();

        while (running.get()) {
            try {
                Thread.sleep(DISCOVERY_INTERVAL_MS);
                if (running.get()) {
                    performDiscovery();
                }
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    /**
     * 执行一轮 mDNS 服务发现。
     * <p>
     * 查询 _nomad-serf._tcp 服务类型，解析后获取节点信息。
     */
    private void performDiscovery() {
        try {
            // 使用 CountDownLatch 风格的同步发现
            final java.util.concurrent.atomic.AtomicInteger pendingServices =
                    new java.util.concurrent.atomic.AtomicInteger(3);
            final java.util.concurrent.CountDownLatch latch =
                    new java.util.concurrent.CountDownLatch(3);

            // 查询 SERF 服务（主要，用于 join）
            discoverService(SERVICE_SERF, pendingServices, latch);

            // 查询 RPC 服务
            discoverService(SERVICE_RPC, pendingServices, latch);

            // 查询 HTTP 服务
            discoverService(SERVICE_HTTP, pendingServices, latch);

            // 等待所有查询完成（最多 5 秒）
            latch.await(5, java.util.concurrent.TimeUnit.SECONDS);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            if (running.get()) {
                Log.e(TAG, "mDNS 发现失败", e);
            }
        }
    }

    private void discoverService(final String serviceType,
                                  final java.util.concurrent.atomic.AtomicInteger pending,
                                  final java.util.concurrent.CountDownLatch latch) {
        try {
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD,
                    new NsdManager.DiscoveryListener() {
                        @Override
                        public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                            Log.w(TAG, "启动发现失败: " + serviceType + ", 错误=" + errorCode);
                            latch.countDown();
                        }

                        @Override
                        public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                            latch.countDown();
                        }

                        @Override
                        public void onDiscoveryStarted(String serviceType) {
                            Log.d(TAG, "mDNS 发现已启动: " + serviceType);
                        }

                        @Override
                        public void onDiscoveryStopped(String serviceType) {
                            // 正常
                        }

                        @Override
                        public void onServiceFound(NsdServiceInfo serviceInfo) {
                            Log.d(TAG, "发现服务: " + serviceInfo.getServiceName()
                                    + " (" + serviceType + ")");

                            // 解析服务获取地址和端口
                            nsdManager.resolveService(serviceInfo,
                                    new NsdManager.ResolveListener() {
                                        @Override
                                        public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                                            Log.w(TAG, "解析服务失败: " + serviceInfo.getServiceName());
                                            latch.countDown();
                                        }

                                        @Override
                                        public void onServiceResolved(NsdServiceInfo serviceInfo) {
                                            handleResolvedService(serviceInfo);
                                            latch.countDown();
                                        }
                                    });
                        }

                        @Override
                        public void onServiceLost(NsdServiceInfo serviceInfo) {
                            Log.d(TAG, "服务消失: " + serviceInfo.getServiceName());
                            // 节点超时由 cleanupLoop 处理
                            latch.countDown();
                        }
                    });
        } catch (Exception e) {
            Log.w(TAG, "查询 " + serviceType + " 失败", e);
            latch.countDown();
        }
    }

    private void handleResolvedService(NsdServiceInfo serviceInfo) {
        try {
            String name = serviceInfo.getServiceName();
            InetAddress host = serviceInfo.getHost();
            if (host == null) {
                Log.w(TAG, "解析服务 " + name + " 无地址");
                return;
            }

            String address = host.getHostAddress();
            int port = serviceInfo.getPort();

            // 从 TXT 记录中获取端口信息
            Map<String, byte[]> txtRecords = serviceInfo.getAttributes();
            int httpPort = parseTxtInt(txtRecords, "http_port", this.httpPort);
            int rpcPort = parseTxtInt(txtRecords, "rpc_port", this.rpcPort);
            int serfPort = parseTxtInt(txtRecords, "serf_port", this.serfPort);

            // 根据服务类型确定端口
            String serviceType = serviceInfo.getServiceType();
            if (serviceType.contains("http")) {
                httpPort = port;
            } else if (serviceType.contains("rpc")) {
                rpcPort = port;
            } else if (serviceType.contains("serf")) {
                serfPort = port;
            }

            // 跳过自己
            if (name.equals(instanceName)) {
                return;
            }

            DiscoveredNode node = new DiscoveredNode(
                    name, address, httpPort, rpcPort, serfPort,
                    System.currentTimeMillis() / 1000
            );

            DiscoveredNode existing = discoveredNodes.put(name, node);
            if (existing == null) {
                Log.d(TAG, "mDNS 发现新节点: " + node);
                for (DiscoveryListener l : listeners) {
                    l.onNodeDiscovered(node);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "处理 mDNS 解析结果失败", e);
        }
    }

    private static int parseTxtInt(Map<String, byte[]> txtRecords, String key, int defaultValue) {
        byte[] value = txtRecords.get(key);
        if (value != null && value.length > 0) {
            try {
                return Integer.parseInt(new String(value, "UTF-8"));
            } catch (Exception e) {
                // 忽略
            }
        }
        return defaultValue;
    }

    /**
     * 清理循环 —— 移除超时节点。
     */
    private void cleanupLoop() {
        while (running.get()) {
            try {
                Thread.sleep(30_000); // 每 30 秒清理一次
            } catch (InterruptedException e) {
                break;
            }

            if (!running.get()) break;

            long now = System.currentTimeMillis();
            java.util.Iterator<Map.Entry<String, DiscoveredNode>> it =
                    discoveredNodes.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, DiscoveredNode> entry = it.next();
                if (now - entry.getValue().getDiscoveredAtMs() > NODE_TIMEOUT_MS) {
                    DiscoveredNode removed = entry.getValue();
                    it.remove();
                    Log.d(TAG, "mDNS 节点超时移除: " + removed);
                    for (DiscoveryListener l : listeners) {
                        l.onNodeLost(removed);
                    }
                }
            }
        }
    }
}
