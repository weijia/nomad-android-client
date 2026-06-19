# Nomad Android Client

A zero-dependency Android Java library for interacting with [HashiCorp Nomad](https://www.nomadproject.io/) HTTP API, with built-in **auto-discovery** of Nomad servers on the local network.

## Features

- **Zero external dependencies** — uses only Android SDK (`java.net.HttpURLConnection` + `org.json`)
- **Auto-discovery** — dual-channel server discovery (UDP broadcast + mDNS)
- **Builder API** — chain-style API for building Nomad job specifications
- **Android-native** — uses `android.net.nsd.NsdManager` for mDNS discovery

## Quick Start

### 1. Add to your project

```groovy
dependencies {
    implementation project(':nomad-android-client')
}
```

### 2. Register a service to Nomad

```java
// Auto-discover Nomad server on the local network
NomadClient.discoverAsync(context, Build.HOST, 30_000, new DiscoveryCallback() {
    @Override
    public void onDiscovered(NomadClient client) {
        try {
            JobSpec job = JobSpec.create("my-web-app")
                .datacenter("dc1")
                .addGroup(
                    TaskGroupSpec.create("web")
                        .addTask(
                            TaskSpec.create("web", "docker")
                                .config("image", "nginx:latest")
                                .resources(256, 500)
                                .addService(
                                    ServiceSpec.create("web-service")
                                        .portLabel("http")
                                        .addCheck("alive", "tcp", 10, 2)
                                )
                                .network(NetworkSpec.create()
                                    .addDynamicPort("http", 80))
                        )
                );
            RegisterResult result = client.registerJob(job);
        } catch (NomadException e) {
            Log.e("Nomad", "Registration failed", e);
        }
    }

    @Override
    public void onTimeout() {
        Log.w("Nomad", "No Nomad server found");
    }

    @Override
    public void onError(Exception e) {
        Log.e("Nomad", "Discovery error", e);
    }
});
```

### 3. Or connect directly

```java
NomadClient client = new NomadClient("http://10.0.1.100:4646");
RegisterResult result = client.registerJob(job);
```

## Auto-Discovery Protocol

The auto-discovery feature is compatible with the [weijia/nomad](https://github.com/weijia/nomad) fork.

### UDP Broadcast (primary)
- **Port**: 4649 (UDP)
- **Broadcast**: `255.255.255.255:4649`
- **Message**: JSON `{"instance":"hostname","http_port":4646,"rpc_port":4647,"serf_port":4648,"address":"192.168.x.x","timestamp":1234567890}`
- **Interval**: 10 seconds broadcast, 30 seconds node timeout

### mDNS (fallback)
- **Services**: `_nomad-http._tcp`, `_nomad-rpc._tcp`, `_nomad-serf._tcp`
- **TXT records**: `http_port=4646`, `rpc_port=4647`, `serf_port=4648`
- **Interval**: 30 seconds query, 10 minutes node timeout

## API Reference

### NomadClient

| Method | Description |
|--------|-------------|
| `registerJob(JobSpec)` | Register a job to Nomad |
| `deleteJob(String)` | Stop and delete a job |
| `listJobs()` | List all registered jobs |
| `getJob(String)` | Get job details |
| `discover(Context, name, timeout)` | Static factory: discover and connect |
| `discoverAsync(..., callback)` | Async factory: discover and connect |

### DiscoveryManager

| Method | Description |
|--------|-------------|
| `start()` | Start dual-channel discovery |
| `stop()` | Stop discovery |
| `waitForServer(timeoutMs)` | Block until a server is found |
| `getHttpAddresses()` | Get all discovered HTTP API addresses |
| `getJoinAddresses()` | Get all discovered Serf join addresses |

## Project Structure

```
nomad-android-client/
├── build.gradle
├── src/main/
│   ├── AndroidManifest.xml
│   └── java/com/nomad/client/
│       ├── NomadClient.java          # HTTP API client
│       ├── NomadException.java       # Exception class
│       ├── RegisterResult.java       # Registration result
│       ├── JobSpec.java              # Job builder
│       ├── TaskGroupSpec.java        # Task group builder
│       ├── TaskSpec.java             # Task builder
│       ├── ServiceSpec.java          # Service builder
│       ├── NetworkSpec.java          # Network/port builder
│       ├── RestartPolicySpec.java    # Restart policy builder
│       ├── BroadcastDiscovery.java   # UDP broadcast discovery
│       ├── MdnsDiscovery.java        # mDNS discovery (Android NSD)
│       ├── DiscoveryManager.java     # Unified discovery manager
│       ├── DiscoveryListener.java    # Discovery callback interface
│       ├── DiscoveryCallback.java    # Async discovery callback
│       ├── DiscoveredNode.java       # Discovered node model
│       └── example/
│           └── NomadExampleActivity.java
```

## License

MIT
