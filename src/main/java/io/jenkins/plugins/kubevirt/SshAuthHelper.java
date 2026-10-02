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

import hudson.model.Computer;
import hudson.slaves.SlaveComputer;
import hudson.model.TaskListener;

import java.io.IOException;
import java.io.PrintStream;
import java.io.StringWriter;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Shared utility for detecting and retrying SSH authentication failures.
 *
 * <p>Cloud-init may still be injecting authorized_keys even though sshd is already
 * running. The Jenkins SSH launcher treats auth failure as permanent, so this helper
 * adds a retry layer on top.</p>
 *
 * <p>The detection works by scanning the agent's log output for messages produced
 * by the SSH launcher (e.g. "Server rejected the N private key(s)…").</p>
 */
public final class SshAuthHelper {

    private static final Logger LOGGER = Logger.getLogger(SshAuthHelper.class.getName());

    private SshAuthHelper() {
        // utility class — not instantiable
    }

    // ==================== Auth Error Detection ====================

    /**
     * Reads a computer's log (from the given byte position) to detect SSH
     * authentication errors.
     *
     * @param computer      the computer whose log to inspect
     * @param sincePosition byte offset to start reading from (0 for the entire log)
     * @return a description of the error if found, or {@code null} if no auth
     *         error was detected
     */
    public static String getSshAuthError(Computer computer, long sincePosition) {
        try {
            StringWriter sw = new StringWriter();
            long written = computer.getLogText().writeLogTo(sincePosition, sw);
            if (written == 0) {
                return null;
            }
            String logContent = sw.toString();

            // Match SSHLauncher's "Server rejected the N private key(s)…" message
            for (String line : logContent.split("\n")) {
                if (line.contains("rejected") && line.contains("private key")) {
                    return line.trim();
                }
            }
            // Fallback: generic authentication failure
            if (logContent.contains("Authentication failed")) {
                return "Authentication failed";
            }
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "Could not read agent log to check for SSH auth errors", e);
        }
        return null;
    }

    // ==================== Auth Retry ====================

    /**
     * A single SSH launch attempt. Implementations may create a fresh
     * {@code SSHLauncher} or reuse an existing one.
     */
    @FunctionalInterface
    public interface SshLaunchAction {
        void launch(SlaveComputer computer, TaskListener listener) throws IOException, InterruptedException;
    }

    /**
     * Launches SSH with automatic retry on authentication failures.
     *
     * <p>The first attempt counts as attempt 1. If it fails with an auth error,
     * up to {@link KubeVirtConfiguration#SSH_AUTH_MAX_RETRIES} additional attempts
     * are made, sleeping {@link KubeVirtConfiguration#SSH_AUTH_RETRY_INTERVAL_SECONDS}
     * seconds between each.</p>
     *
     * <p>Retries stop immediately when:</p>
     * <ul>
     *   <li>The computer comes online (success)</li>
     *   <li>The failure is <em>not</em> an authentication error (different problem)</li>
     *   <li>The maximum number of attempts is reached</li>
     * </ul>
     *
     * @param computer     the agent computer
     * @param listener     task listener for user-facing log messages
     * @param launchAction callback invoked for each SSH launch attempt
     * @param nodeName     node name used in log messages
     */
    public static void launchWithAuthRetry(SlaveComputer computer, TaskListener listener,
                                            SshLaunchAction launchAction, String nodeName)
            throws IOException, InterruptedException {

        int maxAttempts = 1 + KubeVirtConfiguration.SSH_AUTH_MAX_RETRIES;
        int interval = KubeVirtConfiguration.SSH_AUTH_RETRY_INTERVAL_SECONDS;
        PrintStream log = listener.getLogger();

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            long logPosition = computer.getLogText().length();

            launchAction.launch(computer, listener);

            if (computer.isOnline()) {
                return;
            }

            String authError = getSshAuthError(computer, logPosition);
            if (authError == null) {
                return; // Not an auth error — don't retry
            }

            if (attempt < maxAttempts) {
                KubeVirtLog.log(log, "──────────────────────────────────────────────────────────");
                KubeVirtLog.log(log, "SSH authentication failed (attempt " + attempt
                        + "/" + maxAttempts + ").");
                KubeVirtLog.log(log, "Cloud-init may still be setting up authorized_keys.");
                KubeVirtLog.log(log, "Retrying in " + interval + " seconds...");
                KubeVirtLog.log(log, "──────────────────────────────────────────────────────────");
                LOGGER.log(Level.FINE, "[{0}] SSH auth failed (attempt {1}/{2}), "
                                + "retrying in {3}s — cloud-init may still be running",
                        new Object[]{nodeName, attempt, maxAttempts, interval});
                Thread.sleep(interval * 1000L);
            }
        }
    }
}
