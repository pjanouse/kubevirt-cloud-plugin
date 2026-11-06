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

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.Job;
import hudson.model.Label;
import hudson.model.Queue;
import hudson.model.Run;
import hudson.model.labels.LabelAtom;
import hudson.util.StreamTaskListener;
import jenkins.model.Jenkins;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Utilities for interacting with the Jenkins build queue.
 */
public final class QueueUtils {

    private static final Logger LOGGER = Logger.getLogger(QueueUtils.class.getName());

    /** Maximum number of recent builds to scan when resolving a queued {@link Run}. */
    private static final int MAX_BUILDS_TO_SCAN = 10;

    private QueueUtils() {
    }

    /**
     * Cancels queue items whose assigned label is satisfied by the given agent labels.
     *
     * <p>A job waiting for {@code linux} is cancelled when the agent labels include
     * {@code linux} (for example {@code linux kubevirt}). Jobs whose expression is
     * not satisfied (for example {@code windows}) are left in the queue.</p>
     *
     * <p>For Freestyle (and other {@link Job} tasks), the reason is also appended
     * to the build console when a {@link Run} can be resolved. Pipeline
     * {@code node}/{@code agent} waits are not {@link Job}s; those items are
     * cancelled only, and the reason is recorded in the Jenkins system log.</p>
     *
     * @param agentLabels labels of the agent that failed to provision
     * @param reason      a human-readable reason included in log messages
     * @return the number of queue items cancelled
     */
    public static int cancelWaitingItemsSatisfiedBy(Set<LabelAtom> agentLabels, String reason) {
        if (agentLabels == null || agentLabels.isEmpty()) {
            return 0;
        }

        Queue queue = Jenkins.get().getQueue();
        int cancelled = 0;
        for (Queue.Item item : queue.getItems()) {
            Label assigned = item.getAssignedLabel();
            if (assigned != null && assigned.matches(agentLabels)) {
                LOGGER.log(Level.INFO, "Cancelling queue item ''{0}''{1}",
                        new Object[]{
                                item.task.getDisplayName(),
                                reason != null && !reason.isBlank() ? " due to: " + reason : ""
                        });
                if (reason != null && !reason.isBlank() && item.task instanceof Job) {
                    writeProvisioningFailureToBuildLog(resolveRun(item), reason);
                }
                queue.cancel(item);
                cancelled++;
            }
        }
        return cancelled;
    }

    /**
     * Resolves the {@link Run} for a queue item whose task is a {@link Job}.
     *
     * <p>Returns {@code null} for Pipeline agent-allocation tasks and other
     * non-{@link Job} queue tasks. Those builds already have a running
     * {@link Run}; cancelling the queue item fails the {@code node} step, and
     * the provisioning reason is left in the Jenkins log.</p>
     */
    @CheckForNull
    static Run<?, ?> resolveRun(@NonNull Queue.Item item) {
        if (!(item.getTask() instanceof Job<?, ?> job)) {
            return null;
        }

        long queueItemId = item.getId();
        int scanned = 0;
        for (Run<?, ?> run : job.getBuilds()) {
            if (run.getQueueId() == queueItemId) {
                return run;
            }
            if (++scanned >= MAX_BUILDS_TO_SCAN) {
                break;
            }
        }
        return null;
    }

    /**
     * Appends a provisioning failure message to a build's console log.
     */
    static void writeProvisioningFailureToBuildLog(@CheckForNull Run<?, ?> run, @NonNull String reason) {
        if (run == null) {
            return;
        }

        String message = reason + ". Build cancelled.";
        try (OutputStream logOut = Files.newOutputStream(
                run.getRootDir().toPath().resolve("log"),
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
             StreamTaskListener listener = new StreamTaskListener(logOut, StandardCharsets.UTF_8)) {
            KubeVirtLog.error(listener, message);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to write provisioning failure to build log for {0}: {1}",
                    new Object[]{run.getFullDisplayName(), e.getMessage()});
        }
    }
}
