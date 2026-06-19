package com.nomad.client;

/**
 * 自动发现 Nomad 服务器的回调接口。
 */
public interface DiscoveryCallback {

    /**
     * 成功发现 Nomad 服务器并创建客户端。
     * <p>
     * 注意：此回调在子线程中执行，如需更新 UI 请切换到主线程。
     *
     * @param client 已连接到发现的服务器的 NomadClient
     */
    void onDiscovered(NomadClient client);

    /**
     * 超时未发现任何 Nomad 服务器。
     */
    void onTimeout();

    /**
     * 发现过程中发生错误。
     *
     * @param e 异常
     */
    void onError(Exception e);
}
