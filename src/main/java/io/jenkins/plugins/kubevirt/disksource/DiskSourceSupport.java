/*
 * The MIT License — see source repository for full text.
 */

package io.jenkins.plugins.kubevirt.disksource;

import hudson.util.FormValidation;
import io.jenkins.plugins.kubevirt.KubeVirtStaplerSecurity;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

@Restricted(NoExternalUse.class)
final class DiskSourceSupport {

    private DiskSourceSupport() {}

    static String resourceLog(KubeVirtDiskSource.VmProvisioningContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append(", CPU: ").append(context.getCpu());
        if (context.getCpuLimit() != null && !context.getCpuLimit().isEmpty()) {
            sb.append(" (limit: ").append(context.getCpuLimit()).append(")");
        }
        sb.append(", Memory: ").append(context.getMemory());
        if (context.getMemoryLimit() != null && !context.getMemoryLimit().isEmpty()) {
            sb.append(" (limit: ").append(context.getMemoryLimit()).append(")");
        }
        return sb.toString();
    }

    static void requireDataSourceFields(String dataSourceNamespace, String dataSourceName) {
        if (dataSourceNamespace == null || dataSourceNamespace.isBlank()) {
            throw new IllegalArgumentException("DataSource namespace is required but not configured");
        }
        if (dataSourceName == null || dataSourceName.isBlank()) {
            throw new IllegalArgumentException("DataSource name is required but not configured");
        }
    }

    static FormValidation checkDataSourceName(String value, String sourceLabel) {
        if (!KubeVirtStaplerSecurity.canManage()) {
            return FormValidation.ok();
        }
        if (value == null || value.isBlank()) {
            return FormValidation.error("DataSource name is required for " + sourceLabel + ".");
        }
        String name = value.trim();
        if (!name.matches("^[a-z0-9]([-a-z0-9]*[a-z0-9])?$")) {
            return FormValidation.error(
                    "DataSource name must be a valid Kubernetes name (lowercase, alphanumeric, hyphens).");
        }
        if (name.length() > 253) {
            return FormValidation.error("DataSource name must be 253 characters or less.");
        }
        return FormValidation.ok();
    }

    static FormValidation checkDataSourceNamespace(String value, String sourceLabel) {
        if (!KubeVirtStaplerSecurity.canManage()) {
            return FormValidation.ok();
        }
        if (value == null || value.isBlank()) {
            return FormValidation.error("DataSource namespace is required for " + sourceLabel + ".");
        }
        String namespace = value.trim();
        if (!namespace.matches("^[a-z0-9]([-a-z0-9]*[a-z0-9])?$")) {
            return FormValidation.error(
                    "Namespace must be a valid Kubernetes namespace (lowercase, alphanumeric, hyphens).");
        }
        if (namespace.length() > 63) {
            return FormValidation.error("Namespace must be 63 characters or less.");
        }
        return FormValidation.ok();
    }
}
