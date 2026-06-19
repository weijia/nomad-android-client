package com.nomad.client;

import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * UDP 广播发现实现。
 * <p>
 * 对应 Go 代码 nomad/discovery/broadcast.go。
 * <p>
 * 协议细节：
 * <ul>
 *   <li>端口: 4649 (UDP)</li>
 *   <li>广播地址: 255.255.255.255:4649</li>
 *   <li>消息格式: JSON</li>
 *   <li>广播间隔: 10 秒</li>
 *   <li>节点超时: 30 秒</li>
 * </ul>
 * <p>
 * 广播消息 JSON 格式：
 * <pre>
 * {
 *   "instance": "hostname",
 *   "http_port": 4646,
 *   "rpc_port": 4647,
 *   "serf_port": 4648,
 *   "address": "192.168.x.x",
 *   "timestamp": 1234567890
 * }
 * </pre>
 */
public class BroadcastDiscovery {

    private static final String TAG = "NomadBroadcast";

    /** UDP 广播端口 */
    public static final int BROADCAST_PORT = 4649;

    /** 广播间隔（毫秒） */
    private static final long BROADCAST_INTERVAL_MS = 10_000;

    /** UDP 读超时（毫秒） */
    private static final int BROADCAST_TIMEOUT_MS = 2_000;

    /** 节点超时（毫秒）—— 30秒未见则清除 */
    private static final long NODE_TIMEOUT_MS = 30_000;

    /** 清理间隔（毫秒）—— 节点超时的一半 */
    private static final long CLEANUP_INTERVAL_MS = 15_000;

    private final String instanceName;
    private final int httpPort;
    private final int rpcPort;
    private final int serfPort;
    private final String advertiseAddress;

    private final ConcurrentHashMap<String, DiscoveredNode> discoveredNodes = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final CopyOnWriteList<DiscoveryListener> listeners = new CopyOnWriteList<>();

    private DatagramSocket socket;
    private ExecutorService executor;
    private volatile Thread broadcastThread;
    private volatile Thread listenThread;
    private volatile Thread cleanupThread;

    /**
     * 创建 UDP 广播发现实例。
     *
     * @param instanceName     唯一实例名（通常为 hostname）
     * @param httpPort         HTTP API 端口
     * @param rpcPort          RPC 端口
     * @param serfPort         Serf 端口
     * @param advertiseAddress 广播地址（本机 LAN IP），可为 null 则自动检测
     */
    public BroadcastDiscovery(String instanceName, int httpPort, int rpcPort,
                              int serfPort, String advertiseAddress) {
        this.instanceName = instanceName;
        this.httpPort = httpPort;
        this.rpcPort = rpcPort;
        this.serfPort = serfPort;
        this.advertiseAddress = advertiseAddress;
    }

    /**
     * 创建使用默认端口的 UDP 广播发现实例。
     *
     * @param instanceName 唯一实例名
     */
    public BroadcastDiscovery(String instanceName) {
        this(instanceName, 4646, 4647, 4648, null);
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
     * 启动广播发现（同时广播和监听）。
     * <p>
     * 此方法会启动后台线程，非阻塞。
     */
    public void start() {
        if (running.getAndSet(true)) {
            Log.w(TAG, "广播发现已在运行");
            return;
        }

        executor = Executors.newFixedThreadPool(3);

        try {
            socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            socket.setBroadcast(true);
            socket.bind(new InetSocketAddress(BROADCAST_PORT));
            socket.setSoTimeout(BROADCAST_TIMEOUT_MS);
        } catch (SocketException e) {
            running.set(false);
            Log.e(TAG, "无法绑定 UDP 端口 " + BROADCAST_PORT, e);
            return;
        }

        // 启动广播线程
        broadcastThread = new Thread(this::broadcastLoop, "nomad-broadcast-send");
        broadcastThread.setDaemon(true);
        broadcastThread.start();

        // 启动监听线程
        listenThread = new Thread(this::listenLoop, "nomad-broadcast-recv");
        listenThread.setDaemon(true);
        listenThread.start();

        // 启动清理线程
        cleanupThread = new Thread(this::cleanupLoop, "nomad-broadcast-cleanup");
        cleanupThread.setDaemon(true);
        cleanupThread.start();

        Log.d(TAG, "UDP 广播发现已启动, 端口=" + BROADCAST_PORT + ", 实例=" + instanceName);
    }

    /**
     * 停止广播发现。
     */
    public void stop() {
        if (!running.getAndSet(false)) {
            return;
        }

        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
        if (executor != null) {
            executor.shutdownNow();
        }

        // 中断所有线程
        interruptThread(broadcastThread);
        interruptThread(listenThread);
        interruptThread(cleanupThread);

        discoveredNodes.clear();
        Log.d(TAG, "UDP 广播发现已停止");
    }

    // ---- 查询 ----

    /**
     * @return 当前发现的所有活跃节点
     */
    public Map<String, DiscoveredNode> getDiscoveredNodes() {
        return discoveredNodes;
    }

    /**
     * @return 所有活跃节点的 Serf join 地址列表，格式 "host:serf_port"
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
     * 广播循环 —— 每 10 秒发送一次广播。
     */
    private void broadcastLoop() {
        // 启动时立即广播一次
        sendBroadcast();

        while (running.get()) {
            try {
                Thread.sleep(BROADCAST_INTERVAL_MS);
                if (running.get()) {
                    sendBroadcast();
                }
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    private void sendBroadcast() {
        try {
            JSONObject msg = new JSONObject();
            msg.put("instance", instanceName);
            msg.put("http_port", httpPort);
            msg.put("rpc_port", rpcPort);
            msg.put("serf_port", serfPort);
            msg.put("address", advertiseAddress != null ? advertiseAddress : getLocalAddress());
            msg.put("timestamp", System.currentTimeMillis() / 1000);

            byte[] data = msg.toString().getBytes("UTF-8");
            DatagramPacket packet = new DatagramPacket(
                    data, data.length,
                    InetAddress.getByName("255.255.255.255"), BROADCAST_PORT
            );
            socket.send(packet);
            Log.d(TAG, "已发送广播: " + msg.toString());
        } catch (IOException | JSONException e) {
            if (running.get()) {
                Log.e(TAG, "发送广播失败", e);
            }
        }
    }

    /**
     * 监听循环 —— 持续接收广播消息。
     */
    private void listenLoop() {
        byte[] buffer = new byte[4096];

        while (running.get()) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);

                String json = new String(packet.getData(), 0, packet.getLength(), "UTF-8");
                handleBroadcastMessage(json, packet.getAddress().getHostAddress());
            } catch (SocketTimeoutException e) {
                // 正常超时，继续循环
            } catch (IOException e) {
                if (running.get()) {
                    Log.e(TAG, "接收广播失败", e);
                }
            }
        }
    }

    private void handleBroadcastMessage(String json, String senderAddress) {
        try {
            JSONObject msg = new JSONObject(json);

            String instance = msg.getString("instance");
            // 跳过自己
            if (instance.equals(instanceName)) {
                return;
            }

            int httpPort = msg.optInt("http_port", 4646);
            int rpcPort = msg.optInt("rpc_port", 4647);
            int serfPort = msg.optInt("serf_port", 4648);
            long timestamp = msg.optLong("timestamp", 0);

            // 使用消息中的 address，如果为空或 127.0.0.1 则使用发送方地址
            String address = msg.optString("address", "");
            if (address.isEmpty() || address.equals("127.0.0.1")) {
                address = senderAddress;
            }

            DiscoveredNode node = new DiscoveredNode(
                    instance, address, httpPort, rpcPort, serfPort, timestamp
            );

            DiscoveredNode existing = discoveredNodes.put(instance, node);
            if (existing == null) {
                Log.d(TAG, "发现新节点: " + node);
                for (DiscoveryListener l : listeners) {
                    l.onNodeDiscovered(node);
                }
            }
        } catch (JSONException e) {
            Log.w(TAG, "解析广播消息失败: " + json, e);
        }
    }

    /**
     * 清理循环 —— 移除超时节点。
     */
    private void cleanupLoop() {
        while (running.get()) {
            try {
                Thread.sleep(CLEANUP_INTERVAL_MS);
            } catch (InterruptedException e) {
                break;
            }

            if (!running.get()) break;

            long now = System.currentTimeMillis();
            Iterator<Map.Entry<String, DiscoveredNode>> it = discoveredNodes.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, DiscoveredNode> entry = it.next();
                if (now - entry.getValue().getDiscoveredAtMs() > NODE_TIMEOUT_MS) {
                    DiscoveredNode removed = entry.getValue();
                    it.remove();
                    Log.d(TAG, "节点超时移除: " + removed);
                    for (DiscoveryListener l : listeners) {
                        l.onNodeLost(removed);
                    }
                }
            }
        }
    }

    /**
     * 自动检测本机 LAN IP（UDP trick）。
     * <p>
     * 对应 Go 代码中的 getAdvertiseIP()。
     */
    private static String getLocalAddress() {
        try {
            DatagramSocket sock = new DatagramSocket();
            try {
                sock.connect(InetAddress.getByName("8.8.8.8"), 53);
                return sock.getLocalAddress().getHostAddress();
            } finally {
                sock.close();
            }
        } catch (Exception e) {
            Log.w(TAG, "自动检测 LAN IP 失败，回退到 127.0.0.1", e);
            return "127.0.0.1";
        }
    }

    private static void interruptThread(Thread t) {
        if (t != null) {
            t.interrupt();
        }
    }

    /**
     * 简易线程安全的 CopyOnWrite 列表（避免外部依赖）。
     */
    private static class CopyOnWriteList<T> implements Iterable<T> {
        private volatile java.util.List<T> list = new java.util.ArrayList<>();

        synchronized void add(T item) {
            java.util.List<T> copy = new java.util.ArrayList<>(list);
            copy.add(item);
            list = copy;
        }

        synchronized void remove(T item) {
            java.util.List<T> copy = new java.util.ArrayList<>(list);
            copy.remove(item);
            list = copy;
        }

        @Override
        public Iterator<T> iterator() {
            return list.iterator();
        }
    }
}
