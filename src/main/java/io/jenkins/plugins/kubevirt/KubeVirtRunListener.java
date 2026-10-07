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
import hudson.model.AbstractBuild;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Writes VM provisioning log to the build console when a freestyle or matrix job starts on a KubeVirt agent.
 *
 * <p>Agent lifecycle (termination after build or idle timeout) is handled by
 * {@link org.jenkinsci.plugins.durabletask.executors.OnceRetentionStrategy} and
 * {@link hudson.slaves.CloudRetentionStrategy} on {@link KubeVirtAgent}, not by this listener.</p>
 *
 * <p>Pipeline jobs ({@code node('label') { ... }}) do not run on {@link AbstractBuild}; their controller-side
 * {@link Run} has no executor on the KubeVirt agent, so this listener intentionally does not apply to them.</p>
 */
@Extension
public class KubeVirtRunListener extends RunListener<AbstractBuild<?, ?>> {

    private static final Logger LOGGER = Logger.getLogger(KubeVirtRunListener.class.getName());

    @Override
    public void onStarted(AbstractBuild<?, ?> build, TaskListener listener) {
        try {
            Executor executor = build.getExecutor();
            if (executor == null) {
                return;
            }

            Computer computer = executor.getOwner();
            if (!(computer instanceof KubeVirtComputer)) {
                return;
            }

            KubeVirtComputer kvc = (KubeVirtComputer) computer;
            KubeVirtAgent agent = kvc.getNode();

            if (agent == null) {
                return;
            }

            String provisioningInfo = agent.getProvisioningInfo();
            if (provisioningInfo == null || provisioningInfo.isEmpty()) {
                return;
            }

            // Write provisioning log to build console.
            // These lines intentionally use raw println() instead of KubeVirtLog.log()
            // because they draw a fixed-width decorative box. Prepending a timestamp
            // would break the column alignment of the box-drawing characters.
            listener.getLogger().println();
            listener.getLogger().println("╔════════════════════════════════════════════════════════════════════════════╗");
            listener.getLogger().println("║                    KubeVirt - VM Provisioning Log                          ║");
            listener.getLogger().println("╠════════════════════════════════════════════════════════════════════════════╣");
            listener.getLogger().println("║ VM Name: " + padRight(agent.getNodeName(), 66) + "║");
            listener.getLogger().println("║ Cloud: " + padRight(agent.getCloudName(), 68) + "║");
            listener.getLogger().println("╠════════════════════════════════════════════════════════════════════════════╣");

            for (String line : provisioningInfo.split("\n")) {
                if (!line.trim().isEmpty()) {
                    listener.getLogger().println("║ " + padRight(line, 75) + "║");
                }
            }

            listener.getLogger().println("╚════════════════════════════════════════════════════════════════════════════╝");
            listener.getLogger().println();

            LOGGER.log(Level.FINE, "Wrote provisioning log to build {0}", build.getFullDisplayName());

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to write provisioning log to build console", e);
        }
    }

    private String padRight(String s, int length) {
        if (s == null) {
            s = "";
        }
        if (s.length() >= length) {
            return s.substring(0, length);
        }
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < length) {
            sb.append(' ');
        }
        return sb.toString();
    }
}
