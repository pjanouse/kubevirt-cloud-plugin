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

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Label;
import hudson.model.Queue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@WithJenkins
class QueueUtilsTest {

  @SuppressWarnings("unused")
  private JenkinsRule jenkins;

  @Test
  void writesProvisioningFailureToBuildLog(JenkinsRule jenkins) throws Exception {
    FreeStyleProject project = jenkins.createFreeStyleProject();
    FreeStyleBuild build = project.scheduleBuild2(0).waitForStart();

    String reason = "KubeVirt provisioning failed 3 consecutive time(s) for template 'test' (labels 'kubevirt-test-label', limit: 3)";
    QueueUtils.writeProvisioningFailureToBuildLog(build, reason);

    String log = String.join("\n", build.getLog(1000));
    assertTrue(log.contains("[KubeVirt] ERROR: " + reason + ". Build cancelled."));
  }

  @Test
  void resolveRunFindsBuildByQueueId(JenkinsRule jenkins) throws Exception {
    FreeStyleProject project = jenkins.createFreeStyleProject();
    FreeStyleBuild build = project.scheduleBuild2(0).waitForStart();

    long queueId = 42L;
    build.setQueueId(queueId);

    Queue.Item item = Mockito.mock(Queue.Item.class);
    when(item.getTask()).thenReturn(project);
    when(item.getId()).thenReturn(queueId);

    assertEquals(build, QueueUtils.resolveRun(item));
  }

  @Test
  void cancelsJobsWhoseAssignedLabelIsSatisfiedByAgentLabels(JenkinsRule jenkins) throws Exception {
    FreeStyleProject linuxJob = jenkins.createFreeStyleProject("linux-job");
    linuxJob.setAssignedLabel(jenkins.jenkins.getLabel("linux"));

    FreeStyleProject kubevirtJob = jenkins.createFreeStyleProject("kubevirt-job");
    kubevirtJob.setAssignedLabel(jenkins.jenkins.getLabel("kubevirt"));

    FreeStyleProject bothJob = jenkins.createFreeStyleProject("both-job");
    bothJob.setAssignedLabel(jenkins.jenkins.getLabel("linux&&kubevirt"));

    FreeStyleProject windowsJob = jenkins.createFreeStyleProject("windows-job");
    windowsJob.setAssignedLabel(jenkins.jenkins.getLabel("windows"));

    linuxJob.scheduleBuild2(0);
    kubevirtJob.scheduleBuild2(0);
    bothJob.scheduleBuild2(0);
    windowsJob.scheduleBuild2(0);
    jenkins.jenkins.getQueue().maintain();

    assertEquals(4, jenkins.jenkins.getQueue().getItems().length);

    int cancelled = QueueUtils.cancelWaitingItemsSatisfiedBy(
            Label.parse("linux kubevirt"),
            "provisioning failed");

    assertEquals(3, cancelled);
    Queue.Item[] remaining = jenkins.jenkins.getQueue().getItems();
    assertEquals(1, remaining.length);
    assertEquals("windows-job", remaining[0].task.getName());
    jenkins.jenkins.getQueue().clear();
  }

  @Test
  void resolveRunSkipsNonJobQueueTasks() {
    Queue.Item item = Mockito.mock(Queue.Item.class);
    when(item.getTask()).thenReturn(Mockito.mock(Queue.Task.class));

    assertNull(QueueUtils.resolveRun(item));
  }
}
