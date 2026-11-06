/*
 * The MIT License — see source repository for full text.
 */

package io.jenkins.plugins.kubevirt;

import hudson.security.ACL;
import hudson.security.ACLContext;
import java.util.ArrayList;
import java.util.List;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

@WithJenkins
class KubeVirtCloudTemplateOrderTest {

    @SuppressWarnings("unused")
    private JenkinsRule jenkins;

    @Test
    void moveTemplateUpAndDownReordersList(JenkinsRule jenkins) {
        this.jenkins = jenkins;
        KubeVirtCloud cloud = newCloud();
        KubeVirtTemplate first = templateNamed("first");
        KubeVirtTemplate second = templateNamed("second");
        cloud.setTemplates(new ArrayList<>(List.of(first, second)));

        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
            cloud.moveTemplateDown(first.getId());
        }
        assertSame(second, cloud.getTemplates().get(0));
        assertSame(first, cloud.getTemplates().get(1));

        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
            cloud.moveTemplateUp(first.getId());
        }
        assertSame(first, cloud.getTemplates().get(0));
        assertSame(second, cloud.getTemplates().get(1));
    }

    @Test
    void replaceTemplatePreservesListPosition(JenkinsRule jenkins) {
        this.jenkins = jenkins;
        KubeVirtCloud cloud = newCloud();
        KubeVirtTemplate first = templateNamed("first");
        KubeVirtTemplate second = templateNamed("second");
        cloud.setTemplates(new ArrayList<>(List.of(first, second)));

        KubeVirtTemplate updatedSecond = templateNamed("second-v2");
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
            cloud.replaceTemplate(second, updatedSecond);
        }

        assertEquals(2, cloud.getTemplates().size());
        assertSame(first, cloud.getTemplates().get(0));
        assertSame(updatedSecond, cloud.getTemplates().get(1));
    }

    private static KubeVirtCloud newCloud() {
        return new KubeVirtCloud(
                "test-cloud",
                "https://kubernetes.default",
                "creds",
                "default",
                false,
                "10",
                true,
                List.of());
    }

    private static KubeVirtTemplate templateNamed(String name) {
        return new KubeVirtTemplate(
                null,
                name,
                "linux",
                KubeVirtConfiguration.DISK_SOURCE_CONTAINER_DISK,
                "image:latest",
                null,
                null,
                "30Gi",
                KubeVirtConfiguration.ACCESS_MODE_RWX,
                "1",
                "2Gi",
                null,
                null,
                KubeVirtConfiguration.CONNECTION_DIRECT_SSH,
                "creds",
                "22",
                "/home/jenkins",
                null,
                null,
                "10",
                null,
                null,
                null,
                false,
                null,
                null);
    }
}
