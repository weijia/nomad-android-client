package com.nomad.client;

/**
 * 发现的 Nomad 节点信息。
 * <p>
 * 对应 Go 代码中的 BroadcastMessage 结构体。
 */
public class DiscoveredNode {

    private final String instanceName;
    private final String address;
    private final int httpPort;
    private final int rpcPort;
    private final int serfPort;
    private final long discoveredAtMs;
    private final long timestamp;

    public DiscoveredNode(String instanceName, String address,
                          int httpPort, int rpcPort, int serfPort,
                          long timestamp) {
        this.instanceName = instanceName;
        this.address = address;
        this.httpPort = httpPort;
        this.rpcPort = rpcPort;
        this.serfPort = serfPort;
        this.timestamp = timestamp;
        this.discoveredAtMs = System.currentTimeMillis();
    }

    public String getInstanceName() { return instanceName; }
    public String getAddress() { return address; }
    public int getHttpPort() { return httpPort; }
    public int getRpcPort() { return rpcPort; }
    public int getSerfPort() { return serfPort; }
    public long getTimestamp() { return timestamp; }
    public long getDiscoveredAtMs() { return discoveredAtMs; }

    /**
     * @return Serf join 地址，格式 "host:serf_port"
     */
    public String getJoinAddress() {
        return address + ":" + serfPort;
    }

    /**
     * @return HTTP API 地址，格式 "http://host:http_port"
     */
    public String getHttpAddress() {
        return "http://" + address + ":" + httpPort;
    }

    /**
     * @return RPC 地址，格式 "host:rpc_port"
     */
    public String getRpcAddress() {
        return address + ":" + rpcPort;
    }

    @Override
    public String toString() {
        return "DiscoveredNode{" +
                "instance='" + instanceName + '\'' +
                ", address='" + address + '\'' +
                ", http=" + httpPort +
                ", rpc=" + rpcPort +
                ", serf=" + serfPort +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DiscoveredNode that = (DiscoveredNode) o;
        return instanceName.equals(that.instanceName) && address.equals(that.address);
    }

    @Override
    public int hashCode() {
        int result = instanceName.hashCode();
        result = 31 * result + address.hashCode();
        return result;
    }
}
