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

import hudson.Extension;
import hudson.init.Terminator;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.lang.ref.WeakReference;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Tracks active WebSocket tunnel resources to ensure cleanup even when normal
 * disconnection callbacks are not invoked (e.g., Jenkins restart, abnormal termination).
 *
 * <p>This tracker solves the problem of transient resources in {@link VirtctlPortForwardLauncher}
 * being lost after serialization or when connections terminate abnormally without
 * {@code afterDisconnect} being called.</p>
 *
 * <p>Resources are tracked using WeakReferences to the launcher, allowing garbage collection
 * while still enabling cleanup of the underlying network resources.</p>
 */
@Extension
public class PortForwardCleanupTracker {

    private static final Logger LOGGER = Logger.getLogger(PortForwardCleanupTracker.class.getName());

    /**
     * Holds the resources associated with a WebSocket tunnel connection.
     * These are stored separately from the launcher to allow cleanup even after
     * the launcher reference is garbage collected.
     */
    public static class PortForwardResources {
        private final String vmName;
        private final KubeVirtWebSocketTunnel tunnel;
        private final KubernetesClient client;
        private final WeakReference<VirtctlPortForwardLauncher> launcherRef;
        private final long createdAt;

        public PortForwardResources(String vmName, KubeVirtWebSocketTunnel tunnel,
                                     KubernetesClient client,
                                     VirtctlPortForwardLauncher launcher) {
            this.vmName = vmName;
            this.tunnel = tunnel;
            this.client = client;
            this.launcherRef = new WeakReference<>(launcher);
            this.createdAt = System.currentTimeMillis();
        }

        public String getVmName() {
            return vmName;
        }

        public KubeVirtWebSocketTunnel getTunnel() {
            return tunnel;
        }

        public KubernetesClient getClient() {
            return client;
        }

        public boolean isLauncherAlive() {
            return launcherRef.get() != null;
        }

        public long getCreatedAt() {
            return createdAt;
        }

        /**
         * Closes all resources associated with this tunnel.
         */
        public void close() {
            if (tunnel != null) {
                try {
                    tunnel.close();
                    LOGGER.log(Level.FINE, "Closed orphaned WebSocket tunnel for VM: {0}", vmName);
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Error closing WebSocket tunnel for " + vmName, e);
                }
            }

            if (client != null) {
                try {
                    client.close();
                    LOGGER.log(Level.FINE, "Closed orphaned Kubernetes client for VM: {0}", vmName);
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Error closing Kubernetes client for " + vmName, e);
                }
            }
        }
    }

    /**
     * Map of VM name to its tunnel resources.
     * Using ConcurrentHashMap for thread-safety as registrations/unregistrations
     * can happen from different threads.
     */
    private static final Map<String, PortForwardResources> activeResources = new ConcurrentHashMap<>();

    /**
     * Registers tunnel resources for tracking.
     * Call this after successfully establishing a WebSocket tunnel.
     *
     * @param vmName   The name of the VM
     * @param tunnel   The WebSocket tunnel connection
     * @param client   The Kubernetes client
     * @param launcher The launcher that owns these resources
     */
    public static void register(String vmName, KubeVirtWebSocketTunnel tunnel,
                                 KubernetesClient client,
                                 VirtctlPortForwardLauncher launcher) {
        // Clean up any existing resources for this VM first
        PortForwardResources existing = activeResources.remove(vmName);
        if (existing != null) {
            LOGGER.log(Level.WARNING, "Found existing tunnel resources for VM {0}, cleaning up before re-registering", vmName);
            existing.close();
        }

        PortForwardResources resources = new PortForwardResources(vmName, tunnel, client, launcher);
        activeResources.put(vmName, resources);
        LOGGER.log(Level.FINE, "Registered tunnel resources for VM: {0} (total tracked: {1})",
                new Object[]{vmName, activeResources.size()});
    }

    /**
     * Unregisters tunnel resources after normal cleanup.
     * Call this from the launcher's cleanup method.
     *
     * @param vmName The name of the VM
     */
    public static void unregister(String vmName) {
        PortForwardResources removed = activeResources.remove(vmName);
        if (removed != null) {
            LOGGER.log(Level.FINE, "Unregistered tunnel resources for VM: {0} (remaining: {1})",
                    new Object[]{vmName, activeResources.size()});
        }
    }

    /**
     * Cleans up all orphaned resources.
     * A resource is considered orphaned if its launcher WeakReference has been cleared
     * (indicating the launcher was garbage collected without proper cleanup).
     *
     * @return The number of orphaned resources that were cleaned up
     */
    public static int cleanupOrphaned() {
        int cleanedCount = 0;
        Iterator<Map.Entry<String, PortForwardResources>> iterator = activeResources.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<String, PortForwardResources> entry = iterator.next();
            PortForwardResources resources = entry.getValue();

            if (!resources.isLauncherAlive()) {
                LOGGER.log(Level.FINE, "Found orphaned tunnel resources for VM: {0} (launcher GC'd), cleaning up",
                        entry.getKey());
                resources.close();
                iterator.remove();
                cleanedCount++;
            }
        }

        if (cleanedCount > 0) {
            LOGGER.log(Level.FINE, "Cleaned up {0} orphaned tunnel resource(s)", cleanedCount);
        }

        return cleanedCount;
    }

    /**
     * Cleans up all tracked resources.
     * Called during Jenkins shutdown.
     */
    public static void cleanupAll() {
        int count = activeResources.size();
        if (count == 0) {
            return;
        }

        LOGGER.log(Level.FINE, "Cleaning up {0} active tunnel resource(s) during shutdown", count);

        for (Map.Entry<String, PortForwardResources> entry : activeResources.entrySet()) {
            try {
                entry.getValue().close();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error during shutdown cleanup for VM: " + entry.getKey(), e);
            }
        }

        activeResources.clear();
        LOGGER.log(Level.FINE, "Tunnel cleanup completed");
    }

    /**
     * Gets the current count of tracked resources.
     * Useful for monitoring and debugging.
     *
     * @return The number of active tracked resources
     */
    public static int getActiveCount() {
        return activeResources.size();
    }

    /**
     * Checks if a specific VM has tracked resources.
     *
     * @param vmName The VM name to check
     * @return true if resources are being tracked for this VM
     */
    public static boolean hasResources(String vmName) {
        return activeResources.containsKey(vmName);
    }

    /**
     * Jenkins Terminator that runs during shutdown.
     * This ensures all tunnel resources are cleaned up even if
     * agents are not properly disconnected.
     */
    @Terminator
    public static void onShutdown() {
        LOGGER.log(Level.FINE, "Jenkins shutdown detected, cleaning up tunnel resources");
        cleanupAll();
    }
}
