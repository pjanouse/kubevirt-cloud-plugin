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

import hudson.plugins.sshslaves.SSHLauncher;
import hudson.plugins.sshslaves.verifiers.NonVerifyingKeyVerificationStrategy;
import hudson.slaves.ComputerLauncher;

/**
 * Factory for creating SSH launchers for KubeVirt agents.
 * Supports both direct SSH connections and virtctl port-forward tunneling.
 */
public class KubeVirtLauncherFactory {

    /**
     * Creates the appropriate launcher based on the template configuration.
     *
     * @param template   The VM template configuration
     * @param vmName     The name of the VM
     * @param ip         The IP address of the VM (used for direct SSH)
     * @param config     The cloud configuration
     * @param callback   The provisioning callback for logging
     * @return A configured ComputerLauncher instance
     */
    public ComputerLauncher createLauncher(KubeVirtTemplate template, String vmName, String ip,
                                            KubeVirtCloudConfig config,
                                            ProvisioningCallback callback) {
        if (template.isVirtctlSsh()) {
            return createVirtctlLauncher(template, vmName, config, callback);
        } else {
            return createDirectSshLauncher(template, ip, callback);
        }
    }

    /**
     * Creates a direct SSH launcher that connects directly to the VM's IP address.
     *
     * @param template The VM template configuration
     * @param ip       The IP address to connect to
     * @param callback The provisioning callback for logging
     * @return A configured SSHLauncher instance
     */
    public ComputerLauncher createDirectSshLauncher(KubeVirtTemplate template, String ip,
                                                     ProvisioningCallback callback) {
        callback.log("Configuring direct SSH connection to " + ip + ":" + template.getSshPort());

        SSHLauncher launcher = new SSHLauncher(
                ip,                                          // host
                template.getSshPort(),                       // port
                template.getSshCredentialsId(),              // credentialsId
                template.getJavaPath(),                      // javaPath (empty uses PATH)
                null,                                        // jvmOptions
                null,                                        // prefixStartSlaveCmd
                null,                                        // suffixStartSlaveCmd
                KubeVirtConfiguration.SSH_LAUNCH_TIMEOUT_SECONDS_DIRECT,
                KubeVirtConfiguration.SSH_MAX_RETRIES_DIRECT,
                KubeVirtConfiguration.SSH_RETRY_WAIT_TIME_DIRECT,
                new NonVerifyingKeyVerificationStrategy()    // skip host key verification for ephemeral VMs
        );

        callback.log("Direct SSH launcher configured, agent will attempt connection");
        return launcher;
    }

    /**
     * Creates a virtctl port-forward launcher that tunnels SSH through the Kubernetes API.
     *
     * @param template The VM template configuration
     * @param vmName   The name of the VM
     * @param config   The cloud configuration
     * @param callback The provisioning callback for logging
     * @return A configured VirtctlPortForwardLauncher instance
     */
    public ComputerLauncher createVirtctlLauncher(KubeVirtTemplate template, String vmName,
                                                   KubeVirtCloudConfig config,
                                                   ProvisioningCallback callback) {
        callback.log("Configuring virtctl SSH tunnel (via Kubernetes API) to VM: " + vmName);
        callback.log("Note: SSH will be tunneled through the Kubernetes API server - no direct network access needed");

        VirtctlPortForwardLauncher launcher = new VirtctlPortForwardLauncher(
                config.getServerUrl(),
                config.getCloudName(),
                config.getNamespace(),
                config.isIgnoreSsl(),
                vmName,
                template.getSshCredentialsId(),
                template.getJavaPath(),
                template.getRemoteFS(),
                template.getCloudInitWaitSeconds()
        );

        callback.log("virtctl port-forward launcher configured");
        return launcher;
    }
}
