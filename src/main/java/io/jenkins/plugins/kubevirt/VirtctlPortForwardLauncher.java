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

import hudson.model.TaskListener;
import hudson.plugins.sshslaves.SSHLauncher;
import hudson.plugins.sshslaves.verifiers.NonVerifyingKeyVerificationStrategy;
import hudson.slaves.Cloud;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.SlaveComputer;
import hudson.util.Secret;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import jenkins.model.Jenkins;

import java.io.IOException;
import java.io.PrintStream;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A ComputerLauncher that establishes SSH connection through a KubeVirt WebSocket tunnel.
 *
 * <p>This launcher uses the KubeVirt-native portforward subresource API to tunnel SSH
 * traffic through the Kubernetes API server, without requiring direct network access to
 * the VM or discovery of virt-launcher pods.</p>
 *
 * <p>The data flow is:</p>
 * <pre>
 * SSHLauncher ──► 127.0.0.1:localPort ──► WebSocket ──► virt-api ──► VM:22
 * </pre>
 *
 * <p>The API endpoint used is:</p>
 * <pre>
 * wss://&lt;api&gt;/apis/subresources.kubevirt.io/v1/namespaces/{ns}/virtualmachineinstances/{name}/portforward/22/tcp
 * </pre>
 *
 * <p>This approach:</p>
 * <ul>
 *   <li>Eliminates the need to find virt-launcher pods (no pod list/get RBAC needed)</li>
 *   <li>Uses the KubeVirt-native subresource API (same mechanism as {@code virtctl ssh})</li>
 *   <li>Only requires {@code virtualmachineinstances/portforward} RBAC permission</li>
 *   <li>virt-api connects directly to the VM's IP via {@code net.Dial} on the server side</li>
 * </ul>
 *
 * @see KubeVirtWebSocketTunnel
 */
public class VirtctlPortForwardLauncher extends ComputerLauncher {
    private static final Logger LOGGER = Logger.getLogger(VirtctlPortForwardLauncher.class.getName());

    private final String serverUrl;
    /**
     * Name of the Jenkins cloud that owns this launcher.
     * Used to look up the Kubernetes API token at runtime from the Jenkins
     * credential store, so no secret is persisted to the agent XML on disk.
     */
    private final String cloudName;
    private final String namespace;
    private final boolean ignoreSsl;
    private final String vmName;
    private final String sshCredentialsId;
    private final String javaPath;
    private final int cloudInitWaitSeconds;

    @SuppressWarnings("unused")
    private final String remoteFS;

    // Tunnel and client resources to clean up
    private transient KubeVirtWebSocketTunnel tunnel;
    private transient KubernetesClient client;
    private transient SSHLauncher delegateLauncher;

    public VirtctlPortForwardLauncher(String serverUrl, String cloudName, String namespace,
                                       boolean ignoreSsl, String vmName,
                                       String sshCredentialsId, String javaPath, String remoteFS,
                                       int cloudInitWaitSeconds) {
        this.serverUrl = serverUrl;
        this.cloudName = cloudName;
        this.namespace = namespace;
        this.ignoreSsl = ignoreSsl;
        this.vmName = vmName;
        this.sshCredentialsId = sshCredentialsId;
        this.javaPath = javaPath;
        this.remoteFS = remoteFS;
        this.cloudInitWaitSeconds = cloudInitWaitSeconds;
    }

    /**
     * Returns the configured cloud-init wait timeout, falling back to the default when
     * unset (e.g. launchers deserialized from older plugin versions).
     */
    private int effectiveCloudInitWaitSeconds() {
        return cloudInitWaitSeconds > 0
                ? cloudInitWaitSeconds
                : KubeVirtConfiguration.DEFAULT_CLOUD_INIT_WAIT_SECONDS;
    }

    /**
     * Returns the effective cloud name, falling back to the owning
     * {@link KubeVirtAgent}'s cloud name when the launcher was deserialized
     * from an older plugin version that persisted a {@code token} field
     * instead of {@code cloudName}.
     */
    private String effectiveCloudName(SlaveComputer computer) {
        if (cloudName != null) {
            return cloudName;
        }
        if (computer != null && computer.getNode() instanceof KubeVirtAgent) {
            String agentCloudName = ((KubeVirtAgent) computer.getNode()).getCloudName();
            LOGGER.log(Level.FINE, "Launcher for VM {0} has no cloudName (upgraded from older plugin version), "
                    + "falling back to agent cloud name: {1}", new Object[]{vmName, agentCloudName});
            return agentCloudName;
        }
        return null;
    }

    private String resolveToken(String effectiveCloudName) throws IOException {
        if (effectiveCloudName == null) {
            throw new IOException("Cannot resolve Kubernetes token: cloud name is unknown. "
                    + "The launcher for VM '" + vmName + "' may have been deserialized from an "
                    + "incompatible plugin version.");
        }
        Cloud cloud = Jenkins.get().getCloud(effectiveCloudName);
        if (!(cloud instanceof KubeVirtCloud)) {
            throw new IOException("KubeVirt cloud '" + effectiveCloudName
                    + "' not found. It may have been deleted or renamed.");
        }
        KubeVirtCloud kubeVirtCloud = (KubeVirtCloud) cloud;
        String credentialsId = kubeVirtCloud.getCredentialsId();
        try {
            return new KubeVirtClientFactory().lookupToken(credentialsId);
        } catch (IllegalStateException e) {
            throw new IOException("Failed to resolve Kubernetes token for cloud '"
                    + effectiveCloudName + "': " + e.getMessage(), e);
        }
    }

    @Override
    public void launch(SlaveComputer computer, TaskListener listener) throws IOException, InterruptedException {
        PrintStream log = listener.getLogger();
        KubeVirtLog.log(log, "Starting WebSocket tunnel for VM: " + vmName);

        String oauthToken;
        try {
            oauthToken = resolveToken(effectiveCloudName(computer));
        } catch (IOException e) {
            KubeVirtLog.error(listener, "ERROR: Failed to resolve Kubernetes token: "
                    + KubeVirtLog.messageOf(e));
            LOGGER.log(Level.SEVERE, "Failed to resolve Kubernetes token for VM " + vmName, e);
            throw e;
        }

        if (oauthToken == null || oauthToken.isEmpty()) {
            KubeVirtLog.error(listener, "ERROR: Kubernetes token is null or empty! Tunnel will fail.");
            LOGGER.log(Level.SEVERE, "Kubernetes token is null or empty for VM {0}", vmName);
        } else {
            KubeVirtLog.log(log, "Kubernetes API credentials resolved for port-forward tunnel");
        }

        Secret runtimeToken = oauthToken != null && !oauthToken.isEmpty()
                ? Secret.fromString(oauthToken) : null;
        try {
            // Create Kubernetes client (used only for VMI validation)
            Config config = new ConfigBuilder()
                    .withMasterUrl(serverUrl)
                    .withOauthToken(oauthToken)
                    .withNamespace(namespace)
                    .withTrustCerts(ignoreSsl)
                    .build();

            client = new KubernetesClientBuilder()
                    .withConfig(config)
                    .build();

            // Test the connection by getting the server version
            try {
                String version = client.getKubernetesVersion().getGitVersion();
                KubeVirtLog.log(log, "Connected to cluster version: " + version);
            } catch (Exception e) {
                KubeVirtLog.error(listener, "Failed to connect to cluster: " + KubeVirtLog.messageOf(e));
                LOGGER.log(Level.WARNING, "Failed to verify cluster connection", e);
            }

            // Verify the VMI exists and is running
            KubeVirtLog.log(log, "Verifying VMI exists: " + vmName);
            verifyVmiExists(listener);

            // Open WebSocket tunnel to the KubeVirt subresource API
            KubeVirtLog.log(log, "Opening WebSocket tunnel to VMI portforward subresource...");
            KubeVirtLog.log(log, "Endpoint: apis/"
                    + KubeVirtConfiguration.KUBEVIRT_SUBRESOURCE_API_GROUP + "/"
                    + KubeVirtConfiguration.KUBEVIRT_API_VERSION
                    + "/namespaces/" + namespace
                    + "/virtualmachineinstances/" + vmName
                    + "/portforward/" + KubeVirtConfiguration.DEFAULT_SSH_PORT + "/tcp");

            tunnel = KubeVirtWebSocketTunnel.open(
                    serverUrl, runtimeToken, ignoreSsl, namespace, vmName,
                    KubeVirtConfiguration.DEFAULT_SSH_PORT);

            int localPort = tunnel.getLocalPort();

            // Register resources with cleanup tracker for orphan detection
            PortForwardCleanupTracker.register(vmName, tunnel, client, this);

            KubeVirtLog.log(log, "WebSocket tunnel established: localhost:"
                    + localPort + " -> " + vmName + ":" + KubeVirtConfiguration.DEFAULT_SSH_PORT
                    + " (via KubeVirt subresource API)");

            // Wait for cloud-init to complete SSH setup before attempting connection
            int waitSeconds = effectiveCloudInitWaitSeconds();
            KubeVirtLog.log(log, "Waiting for cloud-init to complete SSH setup (timeout: "
                    + waitSeconds + " seconds)...");
            int checkIntervalSeconds = KubeVirtConfiguration.SSH_READINESS_CHECK_INTERVAL_SECONDS;
            boolean sshReady = waitForSshReady(localPort, waitSeconds, checkIntervalSeconds, listener);

            if (!sshReady) {
                KubeVirtLog.log(log, "WARNING: SSH port check timed out after "
                        + waitSeconds + " seconds. Attempting connection anyway...");
            } else {
                KubeVirtLog.log(log, "SSH port is responding, proceeding with connection.");
            }

            // SSH launch with retry for authentication failures.
            // Cloud-init may still be injecting authorized_keys even though sshd is already running.
            // SSHLauncher treats auth failure as permanent, so we add our own retry layer.
            KubeVirtLog.log(log, "Configuring SSH connection...");

            LOGGER.log(Level.FINE, "[{0}] SSH launch config: host=127.0.0.1, port={1}, "
                            + "credentialsId={2}, launchTimeout={3}s, maxRetries={4}, "
                            + "retryWait={5}s, hostKeyVerification=NonVerifying",
                    new Object[]{vmName, String.valueOf(localPort), sshCredentialsId,
                            KubeVirtConfiguration.SSH_LAUNCH_TIMEOUT_SECONDS_PORTFORWARD,
                            KubeVirtConfiguration.SSH_MAX_RETRIES_PORTFORWARD,
                            KubeVirtConfiguration.SSH_RETRY_WAIT_TIME_PORTFORWARD});

            KubeVirtLog.log(log, "Launching SSH connection through WebSocket tunnel...");

            SshAuthHelper.launchWithAuthRetry(computer, listener, (c, l) -> {
                // Create a fresh SSHLauncher for each attempt
                delegateLauncher = new SSHLauncher(
                        "127.0.0.1", localPort, sshCredentialsId, javaPath,
                        null, null, null,
                        KubeVirtConfiguration.SSH_LAUNCH_TIMEOUT_SECONDS_PORTFORWARD,
                        KubeVirtConfiguration.SSH_MAX_RETRIES_PORTFORWARD,
                        KubeVirtConfiguration.SSH_RETRY_WAIT_TIME_PORTFORWARD,
                        new NonVerifyingKeyVerificationStrategy()
                );
                delegateLauncher.launch(c, l);
            }, vmName);

            // Log final launch result to system log
            if (computer.isOnline()) {
                LOGGER.log(Level.FINE, "[{0}] SSH connection established successfully through WebSocket tunnel",
                        vmName);
            } else {
                // Read full log for the final error report
                String sshAuthError = SshAuthHelper.getSshAuthError(computer, 0);
                int maxAuthAttempts = 1 + KubeVirtConfiguration.SSH_AUTH_MAX_RETRIES;

                if (sshAuthError != null) {
                    LOGGER.log(Level.SEVERE, "[{0}] SSH AUTHENTICATION ERROR after {1} attempt(s): {2} "
                                    + "(credentialsId={3}). Verify that: "
                                    + "(1) the SSH private key in the Jenkins credential is correct, "
                                    + "(2) the matching public key is in the VM''s authorized_keys file, "
                                    + "(3) the key format is supported by the VM''s SSH server. "
                                    + "SSHLauncher config: host=127.0.0.1, port={4}, tunnelAlive={5}",
                            new Object[]{vmName, maxAuthAttempts, sshAuthError, sshCredentialsId,
                                    String.valueOf(localPort), tunnel != null && tunnel.isAlive()});
                } else {
                    LOGGER.log(Level.WARNING, "[{0}] SSH launch completed but agent is not online. "
                                    + "SSHLauncher config: host=127.0.0.1, port={1}, credentialsId={2}, "
                                    + "tunnelAlive={3}. "
                                    + "Check the agent''s build console at /computer/{0}/log for SSH error details.",
                            new Object[]{vmName, String.valueOf(localPort), sshCredentialsId,
                                    tunnel != null && tunnel.isAlive()});
                }
            }

        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to launch WebSocket tunnel for " + vmName, e);
            KubeVirtLog.error(listener, "Failed to establish SSH tunnel: " + KubeVirtLog.messageOf(e));
            cleanup();
            throw e;
        } catch (InterruptedException e) {
            LOGGER.log(Level.WARNING, "WebSocket tunnel interrupted for " + vmName, e);
            KubeVirtLog.error(listener, "SSH tunnel interrupted: " + KubeVirtLog.messageOf(e));
            cleanup();
            throw e;
        } catch (KubernetesClientException e) {
            LOGGER.log(Level.SEVERE, "Kubernetes client error during tunnel setup for " + vmName, e);
            KubeVirtLog.error(listener, "Kubernetes error: " + KubeVirtLog.messageOf(e));
            cleanup();
            throw new IOException("Kubernetes client error during tunnel setup", e);
        }
    }

    /**
     * Verifies the VirtualMachineInstance exists and provides diagnostic info if not.
     *
     * @param listener Task listener for logging
     * @throws IOException If the VMI is not found or not in a valid state
     */
    @SuppressWarnings("unchecked")
    private void verifyVmiExists(TaskListener listener) throws IOException {
        PrintStream log = listener.getLogger();

        ResourceDefinitionContext vmiContext = new ResourceDefinitionContext.Builder()
                .withGroup(KubeVirtConfiguration.KUBEVIRT_API_GROUP)
                .withVersion(KubeVirtConfiguration.KUBEVIRT_API_VERSION)
                .withKind("VirtualMachineInstance")
                .withPlural("virtualmachineinstances")
                .withNamespaced(true)
                .build();

        GenericKubernetesResource vmi = client.genericKubernetesResources(vmiContext)
                .inNamespace(namespace)
                .withName(vmName)
                .get();

        if (vmi == null) {
            // Check if the VM itself exists (VMI may not have been created yet)
            ResourceDefinitionContext vmContext = new ResourceDefinitionContext.Builder()
                    .withGroup(KubeVirtConfiguration.KUBEVIRT_API_GROUP)
                    .withVersion(KubeVirtConfiguration.KUBEVIRT_API_VERSION)
                    .withKind("VirtualMachine")
                    .withPlural("virtualmachines")
                    .withNamespaced(true)
                    .build();

            GenericKubernetesResource vm = client.genericKubernetesResources(vmContext)
                    .inNamespace(namespace)
                    .withName(vmName)
                    .get();

            if (vm == null) {
                throw new IOException("Neither VM nor VMI found: " + vmName
                        + " in namespace " + namespace + ". The VM may have been deleted.");
            } else {
                throw new IOException("VM exists but VMI '" + vmName
                        + "' not found in namespace " + namespace
                        + ". The VM may not be running yet.");
            }
        }

        // Check VMI phase
        var additionalProps = vmi.getAdditionalProperties();
        if (additionalProps != null && additionalProps.containsKey("status")) {
            var status = (Map<String, Object>) additionalProps.get("status");
            if (status != null) {
                String phase = (String) status.get("phase");
                KubeVirtLog.log(log, "VMI phase: " + phase);

                if (!"Running".equals(phase)) {
                    throw new IOException("VMI '" + vmName + "' is in phase '" + phase
                            + "', expected 'Running'. The KubeVirt portforward subresource requires "
                            + "the VMI to be running (virt-api needs the VM's network IP).");
                }
            }
        }

        KubeVirtLog.log(log, "VMI verified: " + vmName);
    }

    /**
     * Waits for SSH to become ready by probing the port through the tunnel.
     * This allows cloud-init time to set up SSH keys and start sshd.
     *
     * @param port             The local port to check
     * @param maxWaitSeconds   Maximum time to wait
     * @param intervalSeconds  Time between checks
     * @param listener         Task listener for logging
     * @return true if SSH is responding, false if timeout
     */
    private boolean waitForSshReady(int port, int maxWaitSeconds, int intervalSeconds, TaskListener listener) {
        PrintStream log = listener.getLogger();
        int attempts = maxWaitSeconds / intervalSeconds;
        for (int i = 1; i <= attempts; i++) {
            try (java.net.Socket socket = new java.net.Socket()) {
                socket.connect(new java.net.InetSocketAddress("127.0.0.1", port),
                        KubeVirtConfiguration.SSH_SOCKET_TIMEOUT_MS);

                // Try to read the SSH banner to confirm sshd is fully up
                socket.setSoTimeout(KubeVirtConfiguration.SSH_SOCKET_TIMEOUT_MS);
                java.io.InputStream in = socket.getInputStream();
                byte[] buffer = new byte[KubeVirtConfiguration.SSH_BANNER_BUFFER_SIZE];
                int bytesRead = in.read(buffer);
                if (bytesRead > 0) {
                    String banner = new String(buffer, 0, bytesRead, java.nio.charset.StandardCharsets.UTF_8).trim();
                    if (banner.startsWith("SSH-")) {
                        KubeVirtLog.log(log, "SSH banner received: "
                                + banner.split("\n")[0]);
                        return true;
                    }
                }
            } catch (java.net.SocketTimeoutException e) {
                KubeVirtLog.log(log, "Waiting for SSH... (attempt "
                        + i + "/" + attempts + ", cloud-init in progress)");
            } catch (java.net.ConnectException e) {
                KubeVirtLog.log(log, "Waiting for SSH... (attempt "
                        + i + "/" + attempts + ", sshd not started)");
            } catch (IOException e) {
                String detail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                // Provide user-friendly context for common transient errors during VM boot
                String hint;
                String lower = detail.toLowerCase();
                if (lower.contains("no route to host") || lower.contains("dialing vm")) {
                    hint = "VM guest network not ready yet";
                } else if (lower.contains("connection refused")) {
                    hint = "sshd not started";
                } else if (lower.contains("connection reset")) {
                    hint = "connection reset, retrying";
                } else {
                    hint = detail;
                }
                KubeVirtLog.log(log, "Waiting for SSH... (attempt "
                        + i + "/" + attempts + ", " + hint + ")");
            }

            try {
                Thread.sleep(intervalSeconds * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    @Override
    public void afterDisconnect(SlaveComputer computer, TaskListener listener) {
        KubeVirtLog.log(listener.getLogger(), "Cleaning up WebSocket tunnel for VM: " + vmName);

        if (delegateLauncher != null) {
            delegateLauncher.afterDisconnect(computer, listener);
        }

        cleanup();
    }

    private void cleanup() {
        // Unregister from tracker first (before nullifying references)
        PortForwardCleanupTracker.unregister(vmName);

        if (tunnel != null) {
            try {
                tunnel.close();
                LOGGER.log(Level.FINE, "Closed WebSocket tunnel for VM: " + vmName);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error closing WebSocket tunnel for " + vmName, e);
            }
            tunnel = null;
        }

        if (client != null) {
            try {
                client.close();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error closing Kubernetes client", e);
            }
            client = null;
        }
    }

    @Override
    public void beforeDisconnect(SlaveComputer computer, TaskListener listener) {
        if (delegateLauncher != null) {
            delegateLauncher.beforeDisconnect(computer, listener);
        }
    }
}
