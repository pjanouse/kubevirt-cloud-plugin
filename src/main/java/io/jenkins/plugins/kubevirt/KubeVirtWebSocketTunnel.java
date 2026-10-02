/*
 * The MIT License
 *
 * Copyright (c) Red Hat, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package io.jenkins.plugins.kubevirt;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.util.Secret;

/**
 * Establishes a WebSocket tunnel to the KubeVirt subresource API and exposes it
 * as a local TCP port that {@link hudson.plugins.sshslaves.SSHLauncher} can connect to.
 *
 * <p>This replaces the previous approach of finding virt-launcher pods and using
 * Kubernetes core pod port-forward. Instead, it uses the KubeVirt-native portforward
 * subresource endpoint:</p>
 *
 * <pre>
 * wss://&lt;api&gt;/apis/subresources.kubevirt.io/v1/namespaces/{ns}/virtualmachineinstances/{name}/portforward/{port}/tcp
 * </pre>
 *
 * <p>The tunnel accepts multiple sequential TCP connections on the local port.
 * Each accepted connection triggers a new WebSocket to virt-api, which in turn
 * opens a direct TCP connection to the VM's IP. This supports both the SSH readiness
 * probe (short-lived) and the actual SSH session (long-lived).</p>
 *
 * <p>Data flow:</p>
 * <pre>
 * SSHLauncher ──► 127.0.0.1:localPort ──► WebSocket ──► virt-api ──► net.Dial(VM_IP:22)
 * </pre>
 *
 * @see <a href="virtctl-ssh-internals.md">virtctl-ssh-internals.md §5–6</a>
 */
public class KubeVirtWebSocketTunnel implements Closeable {

    private static final Logger LOGGER = Logger.getLogger(KubeVirtWebSocketTunnel.class.getName());

    private final ServerSocket serverSocket;
    private final ExecutorService executor;
    private final HttpClient httpClient;
    private final String wsUrl;
    /** Runtime Kubernetes API token; this class is not persisted as Jenkins configuration. */
    @SuppressWarnings("lgtm[jenkins/plaintext-storage]")
    private final Secret token;
    private final AtomicBoolean running = new AtomicBoolean(true);

    /**
     * Tracks active TCP connections so {@link #close()} can force-close them.
     * This is necessary because {@code InputStream.read()} on a plain socket does
     * not respond to {@code Thread.interrupt()}, so {@code executor.shutdownNow()}
     * alone cannot unblock pump threads. Closing the socket causes {@code read()}
     * to throw an {@code IOException}, which exits the pump and triggers cleanup.
     */
    private final Set<Socket> activeConnections = ConcurrentHashMap.newKeySet();

    private KubeVirtWebSocketTunnel(ServerSocket serverSocket, HttpClient httpClient,
                                     String wsUrl, Secret token, String vmiName) {
        this.serverSocket = serverSocket;
        this.httpClient = httpClient;
        this.wsUrl = wsUrl;
        this.token = token;
        AtomicInteger threadIndex = new AtomicInteger();
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "kubevirt-ws-tunnel-" + vmiName + "-" + threadIndex.getAndIncrement());
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Opens a new WebSocket tunnel to a KubeVirt VMI's port.
     *
     * <p>This method binds a local TCP server socket on an ephemeral port and starts
     * an accept loop in the background. Each accepted TCP connection will trigger a
     * new WebSocket connection to the KubeVirt subresource API.</p>
     *
     * @param serverUrl  Kubernetes API server URL (e.g. {@code https://api.cluster:6443})
     * @param token      OAuth bearer token for Kubernetes API authentication
     * @param ignoreSsl  Whether to skip TLS certificate verification
     * @param namespace  Kubernetes namespace of the VMI
     * @param vmiName    Name of the VirtualMachineInstance
     * @param targetPort Target port on the VM (e.g. 22 for SSH)
     * @return A tunnel instance with a local port ready for connections
     * @throws IOException If the local server socket cannot be created
     */
    public static KubeVirtWebSocketTunnel open(String serverUrl, Secret token, boolean ignoreSsl,
                                                String namespace, String vmiName,
                                                int targetPort) throws IOException {
        String wsUrl = buildSubresourceUrl(serverUrl, namespace, vmiName, targetPort);

        LOGGER.log(Level.FINE, "Opening WebSocket tunnel to VMI {0}/{1} port {2,number,#}",
                new Object[]{namespace, vmiName, targetPort});
        LOGGER.log(Level.FINE, "WebSocket URL: {0}", wsUrl);

        // Build a single HttpClient to be reused for all WebSocket connections.
        // Each HttpClient carries its own thread pool and connection pool, so creating
        // one per TCP connection would leak threads and sockets.
        HttpClient httpClient = buildHttpClient(ignoreSsl);

        // Bind local server socket on loopback, ephemeral port, backlog of 2
        ServerSocket ss = new ServerSocket(0, 2, InetAddress.getLoopbackAddress());
        ss.setReuseAddress(true);

        KubeVirtWebSocketTunnel tunnel = new KubeVirtWebSocketTunnel(ss, httpClient, wsUrl, token, vmiName);
        tunnel.startAcceptLoop();

        LOGGER.log(Level.FINE, "WebSocket tunnel listening on 127.0.0.1:{0,number,#} for VMI {1}/{2}",
                new Object[]{ss.getLocalPort(), namespace, vmiName});

        return tunnel;
    }

    /**
     * Returns the local TCP port that clients (e.g. SSHLauncher) should connect to.
     *
     * @return The local port number
     */
    public int getLocalPort() {
        return serverSocket.getLocalPort();
    }

    /**
     * Returns whether the tunnel is still running and accepting connections.
     *
     * @return {@code true} if the tunnel is active
     */
    public boolean isAlive() {
        return running.get() && !serverSocket.isClosed();
    }

    /**
     * Closes the tunnel, stopping the accept loop and cleaning up all resources.
     */
    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) {
            return; // Already closed
        }

        LOGGER.log(Level.FINE, "Closing WebSocket tunnel on port {0,number,#}", serverSocket.getLocalPort());

        // Close the server socket to unblock accept()
        try {
            serverSocket.close();
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "Error closing server socket", e);
        }

        // Close all active TCP connections to unblock pump threads that are
        // blocked on InputStream.read(). This causes read() to throw an
        // IOException, which exits pumpTcpToWebSocket() and triggers the
        // finally block in handleConnection() for WebSocket cleanup.
        for (Socket s : activeConnections) {
            try {
                s.close();
            } catch (IOException e) {
                LOGGER.log(Level.FINE, "Error closing active TCP connection during shutdown", e);
            }
        }

        // Shut down the executor
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                LOGGER.log(Level.WARNING, "WebSocket tunnel executor did not terminate in time");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Builds the KubeVirt subresource portforward WebSocket URL.
     *
     * <p>Converts the server URL scheme from {@code https://} to {@code wss://}
     * (or {@code http://} to {@code ws://}) and appends the subresource path.</p>
     *
     * @param serverUrl  Kubernetes API server URL
     * @param namespace  Namespace of the VMI
     * @param vmiName    Name of the VMI
     * @param targetPort Target port on the VM
     * @return The full WebSocket URL for the portforward subresource
     */
    static String buildSubresourceUrl(String serverUrl, String namespace,
                                       String vmiName, int targetPort) {
        // Convert HTTP(S) scheme to WS(S)
        String base = serverUrl;
        if (base.startsWith("https://")) {
            base = "wss://" + base.substring("https://".length());
        } else if (base.startsWith("http://")) {
            base = "ws://" + base.substring("http://".length());
        }

        // Remove trailing slash
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }

        return base + "/apis/"
                + KubeVirtConfiguration.KUBEVIRT_SUBRESOURCE_API_GROUP + "/"
                + KubeVirtConfiguration.KUBEVIRT_API_VERSION
                + "/namespaces/" + namespace
                + "/virtualmachineinstances/" + vmiName
                + "/portforward/" + targetPort + "/tcp";
    }

    /**
     * Starts the background accept loop that listens for incoming TCP connections.
     * Each accepted connection is handled in its own thread.
     */
    private void startAcceptLoop() {
        executor.submit(() -> {
            while (running.get()) {
                try {
                    Socket tcpSocket = serverSocket.accept();
                    LOGGER.log(Level.FINE, "Accepted TCP connection from {0}",
                            tcpSocket.getRemoteSocketAddress());
                    executor.submit(() -> handleConnection(tcpSocket));
                } catch (IOException e) {
                    if (running.get()) {
                        LOGGER.log(Level.WARNING, "Error accepting connection on tunnel", e);
                    }
                    // If not running, the socket was closed intentionally
                }
            }
        });
    }

    /**
     * Handles a single TCP connection by establishing a WebSocket to virt-api
     * and piping data bidirectionally.
     *
     * @param tcpSocket The accepted TCP connection
     */
    private void handleConnection(Socket tcpSocket) {
        activeConnections.add(tcpSocket);
        WebSocket ws = null;
        try {
            InputStream tcpIn = tcpSocket.getInputStream();
            OutputStream tcpOut = tcpSocket.getOutputStream();

            // Establish WebSocket to KubeVirt subresource API
            ws = connectWebSocket(tcpOut);

            // TCP → WebSocket pump (blocking reads)
            pumpTcpToWebSocket(tcpIn, ws);

        } catch (IOException e) {
            if (running.get()) {
                String errorDetail = e.getMessage() != null ? e.getMessage()
                        : e.getClass().getSimpleName()
                        + (e.getCause() != null ? " caused by " + e.getCause() : "");
                if (isVmBootError(errorDetail)) {
                    // Expected during VM boot — guest OS is still configuring its
                    // network interface. Log a clean one-liner at FINE; the
                    // waitForSshReady loop already provides user-facing progress.
                    LOGGER.log(Level.FINE, "Portforward not ready (VM booting): {0}",
                            extractVmDialError(errorDetail));
                } else {
                    LOGGER.log(Level.WARNING, "WebSocket tunnel connection failed for {0}: {1}",
                            new Object[]{wsUrl, errorDetail});
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error in WebSocket tunnel connection", e);
        } finally {
            activeConnections.remove(tcpSocket);
            // Close WebSocket
            if (ws != null) {
                try {
                    ws.sendClose(WebSocket.NORMAL_CLOSURE, "done");
                } catch (Exception e) {
                    LOGGER.log(Level.FINE, "Error sending WebSocket close", e);
                }
            }
            // Close TCP socket
            try {
                tcpSocket.close();
            } catch (IOException e) {
                LOGGER.log(Level.FINE, "Error closing TCP socket", e);
            }
        }
    }

    /**
     * Establishes a WebSocket connection to the KubeVirt subresource API.
     *
     * <p>The WebSocket uses the {@code plain.kubevirt.io} subprotocol and authenticates
     * with a Bearer token. The returned WebSocket will write incoming binary data
     * directly to the provided TCP output stream.</p>
     *
     * @param tcpOut The TCP output stream to forward WebSocket data to
     * @return The connected WebSocket
     * @throws IOException If the connection fails
     */
    private WebSocket connectWebSocket(OutputStream tcpOut) throws IOException {
        if (token == null) {
            throw new IOException("Kubernetes OAuth token is not configured for WebSocket tunnel");
        }
        try {
            CompletableFuture<WebSocket> wsFuture = httpClient.newWebSocketBuilder()
                    .header("Authorization", "Bearer " + token.getPlainText())
                    .subprotocols(KubeVirtConfiguration.KUBEVIRT_WS_SUBPROTOCOL)
                    .connectTimeout(java.time.Duration.ofSeconds(
                            KubeVirtConfiguration.WS_CONNECT_TIMEOUT_SECONDS))
                    .buildAsync(URI.create(wsUrl), new WebSocketToTcpListener(tcpOut));

            WebSocket ws = wsFuture.get(
                    KubeVirtConfiguration.WS_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            LOGGER.log(Level.FINE, "WebSocket connected to {0}", wsUrl);
            return ws;

        } catch (java.util.concurrent.TimeoutException e) {
            throw new IOException("Timeout connecting WebSocket to KubeVirt API: " + wsUrl, e);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof WebSocketHandshakeException hsEx) {
                HttpResponse<?> response = hsEx.getResponse();
                int statusCode = response.statusCode();
                String body = response.body() != null ? response.body().toString() : "(no body)";

                // Extract the meaningful message from the K8s Status JSON body
                // instead of including the entire raw JSON in the error message.
                String k8sMessage = extractK8sStatusMessage(body);
                String summary = k8sMessage != null ? k8sMessage : body;

                String msg;
                if (statusCode == 403) {
                    msg = "Portforward rejected (HTTP 403): " + summary
                            + ". Check RBAC: the service account needs 'virtualmachineinstances/portforward'"
                            + " permission on API group 'subresources.kubevirt.io'";
                } else if (statusCode == 404) {
                    msg = "Portforward not found (HTTP 404): " + summary
                            + ". Check that KubeVirt is installed and the VMI is running.";
                } else if (statusCode == 500) {
                    msg = "Portforward failed (HTTP 500): " + summary;
                } else {
                    msg = "WebSocket handshake rejected (HTTP " + statusCode + "): " + summary;
                }

                // Full body available at FINE for debugging
                LOGGER.log(Level.FINE, "Full API response body for {0}: {1}",
                        new Object[]{wsUrl, body});

                throw new IOException(msg, hsEx);
            }
            if (cause instanceof IOException ioEx) {
                throw ioEx;
            }
            throw new IOException("Failed to connect WebSocket to KubeVirt API: "
                    + (cause != null ? cause.getMessage() : e.getMessage()), cause != null ? cause : e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while connecting WebSocket", e);
        }
    }

    /**
     * Determines whether an error message indicates a transient VM boot condition
     * rather than a persistent failure.
     *
     * <p>During VM boot, the guest OS has not yet configured its network interface.
     * The KubeVirt portforward API tries to dial the VM's IP (via masquerade DNAT),
     * but ARP resolution fails because the guest interface is unconfigured.
     * This results in "no route to host" or "connection refused" errors that
     * resolve themselves once the guest OS completes network setup.</p>
     *
     * @param errorMessage The error detail string to check
     * @return {@code true} if the error is a known transient boot condition
     */
    private static boolean isVmBootError(String errorMessage) {
        if (errorMessage == null) {
            return false;
        }
        String lower = errorMessage.toLowerCase();
        return lower.contains("no route to host")
                || lower.contains("connection refused")
                || (lower.contains("500") && lower.contains("dialing vm"));
    }

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * Extracts the {@code "message"} field from a Kubernetes Status JSON body.
     *
     * <p>The KubeVirt API returns errors as Kubernetes Status objects:
     * <pre>{"kind":"Status","message":"Internal error occurred: dialing VM: ...","reason":"InternalError",...}</pre>
     * This method extracts just the human-readable message.</p>
     *
     * @param body the raw HTTP response body
     * @return the extracted message, or {@code null} if not found or not valid JSON
     */
    static String extractK8sStatusMessage(String body) {
        if (body == null) {
            return null;
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(body);
            JsonNode messageNode = root.get("message");
            if (messageNode != null && messageNode.isTextual()) {
                String message = messageNode.asText().trim();
                return message.isEmpty() ? null : message;
            }
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Could not parse K8s status JSON: {0}", e.getMessage());
        }
        return null;
    }

    /**
     * Extracts the concise dial error from a portforward error message.
     *
     * <p>For messages like {@code "Portforward failed (HTTP 500): Internal error occurred:
     * dialing VM: dial tcp 10.244.0.19:22: connect: no route to host"}, this extracts
     * just the network error portion: {@code "no route to host (10.244.0.19:22)"}.</p>
     *
     * @param errorMessage The full error message
     * @return A concise description of the dial error
     */
    private static String extractVmDialError(String errorMessage) {
        if (errorMessage == null) {
            return "unknown";
        }
        // Try to extract "connect: <reason>" from "dial tcp <addr>: connect: <reason>"
        String lower = errorMessage.toLowerCase();
        int connectIdx = lower.indexOf("connect:");
        if (connectIdx >= 0) {
            String reason = errorMessage.substring(connectIdx + "connect:".length()).trim();
            // Remove any trailing content after the reason (e.g., URL suffix)
            int endIdx = reason.indexOf('\n');
            if (endIdx < 0) {
                endIdx = reason.length();
            }
            return reason.substring(0, endIdx).trim();
        }
        // Try to extract just "dialing VM: ..." portion
        int dialIdx = lower.indexOf("dialing vm:");
        if (dialIdx >= 0) {
            String reason = errorMessage.substring(dialIdx).trim();
            int endIdx = reason.indexOf('\n');
            if (endIdx < 0) {
                endIdx = reason.length();
            }
            return reason.substring(0, endIdx).trim();
        }
        // Fallback: return a truncated version
        return errorMessage.length() > 120
                ? errorMessage.substring(0, 120) + "..."
                : errorMessage;
    }

    /**
     * Builds an {@link HttpClient} configured for WebSocket connections.
     * The returned client is intended to be reused for all WebSocket connections
     * within a single tunnel instance, avoiding the overhead of creating a new
     * thread pool and connection pool per connection.
     *
     * @param ignoreSsl Whether to skip TLS certificate verification
     * @return A configured HttpClient instance
     * @throws IOException If the SSL context cannot be created
     */
    @SuppressWarnings("lgtm[jenkins/unsafe-calls]")
    private static HttpClient buildHttpClient(boolean ignoreSsl) throws IOException {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(
                        KubeVirtConfiguration.WS_CONNECT_TIMEOUT_SECONDS));

        if (ignoreSsl) {
            try {
                SSLContext sslContext = SSLContext.getInstance("TLS");
                // lgtm[jenkins/unsafe-calls] -- limited to this HttpClient when cloud admin enables ignoreSsl
                sslContext.init(null, new TrustManager[]{new TrustAllCertsManager()}, null);
                builder.sslContext(sslContext);
            } catch (NoSuchAlgorithmException | KeyManagementException e) {
                throw new IOException("Failed to create permissive SSL context", e);
            }
        }

        return builder.build();
    }

    /**
     * Reads data from the TCP socket and sends it as WebSocket binary frames.
     * Blocks until the TCP input stream is closed or an error occurs.
     *
     * @param tcpIn The TCP input stream to read from
     * @param ws    The WebSocket to send data to
     * @throws IOException If a read or send error occurs
     */
    private void pumpTcpToWebSocket(InputStream tcpIn, WebSocket ws) throws IOException {
        byte[] buffer = new byte[KubeVirtConfiguration.WS_TUNNEL_BUFFER_SIZE];
        int bytesRead;
        while (running.get() && (bytesRead = tcpIn.read(buffer)) != -1) {
            try {
                ByteBuffer data = ByteBuffer.wrap(buffer, 0, bytesRead);
                ws.sendBinary(data, true).join();
            } catch (Exception e) {
                if (running.get()) {
                    throw new IOException("Error sending data to WebSocket", e);
                }
                break;
            }
        }
    }

    /**
     * WebSocket listener that forwards received binary data to a TCP output stream.
     *
     * <p>The KubeVirt portforward subresource sends raw TCP data as WebSocket binary
     * frames with no framing or multiplexing (the {@code plain.kubevirt.io} subprotocol).
     * Each binary message is written directly to the TCP output stream.</p>
     */
    private class WebSocketToTcpListener implements WebSocket.Listener {

        private final OutputStream tcpOut;

        WebSocketToTcpListener(OutputStream tcpOut) {
            this.tcpOut = tcpOut;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            LOGGER.log(Level.FINE, "WebSocket opened, requesting first message");
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            try {
                byte[] bytes = new byte[data.remaining()];
                data.get(bytes);
                tcpOut.write(bytes);
                tcpOut.flush();
            } catch (IOException e) {
                LOGGER.log(Level.FINE, "Error writing WebSocket data to TCP: {0}", e.getMessage());
                return CompletableFuture.failedFuture(e);
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            // KubeVirt portforward uses binary frames; log any text frames for debugging
            LOGGER.log(Level.FINE, "Received unexpected text frame: {0}", data);
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            LOGGER.log(Level.FINE, "WebSocket closed: code={0,number,#}, reason={1}",
                    new Object[]{statusCode, reason});
            try {
                tcpOut.close();
            } catch (IOException e) {
                LOGGER.log(Level.FINE, "Error closing TCP output on WebSocket close", e);
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            if (running.get()) {
                LOGGER.log(Level.WARNING, "WebSocket error: {0}", error.getMessage());
            }
            try {
                tcpOut.close();
            } catch (IOException e) {
                LOGGER.log(Level.FINE, "Error closing TCP output on WebSocket error", e);
            }
        }
    }

    /**
     * Trust manager that accepts all certificates.
     * Used when {@code ignoreSsl} is true.
     */
    @SuppressWarnings("java:S4830") // Intentionally trusting all certs when configured
    private static class TrustAllCertsManager implements X509TrustManager {
        @Override
        @SuppressFBWarnings(value = "WEAK_TRUST_MANAGER",
                justification = "Used only when the cloud administrator explicitly enables ignoreSsl")
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
            // Trust all
        }

        @Override
        @SuppressFBWarnings(value = "WEAK_TRUST_MANAGER",
                justification = "Used only when the cloud administrator explicitly enables ignoreSsl")
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
            // Trust all
        }

        @Override
        @SuppressFBWarnings(value = "WEAK_TRUST_MANAGER",
                justification = "Used only when the cloud administrator explicitly enables ignoreSsl")
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
