package com.nomad.client;

/**
 * 节点发现监听器。
 */
public interface DiscoveryListener {

    /**
     * 发现新节点时回调。
     *
     * @param node 发现的节点
     */
    void onNodeDiscovered(DiscoveredNode node);

    /**
     * 节点超时移除时回调。
     *
     * @param node 被移除的节点
     */
    void onNodeLost(DiscoveredNode node);
}
