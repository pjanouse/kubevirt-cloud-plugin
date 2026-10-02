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

package io.jenkins.plugins.kubevirt.internal;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceBuilder;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import io.jenkins.plugins.kubevirt.KubeVirtConfiguration;
import io.jenkins.plugins.kubevirt.ProvisioningCallback;
import jenkins.model.Jenkins;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * Helper class for interacting with KubeVirt VirtualMachines on OpenShift/Kubernetes.
 * Uses the generic Kubernetes resource API to work with KubeVirt CRDs.
 */
public class KubeVirt implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(KubeVirt.class.getName());

    private static final CustomResourceDefinitionContext VM_CONTEXT = new CustomResourceDefinitionContext.Builder()
            .withGroup(KubeVirtConfiguration.KUBEVIRT_API_GROUP)
            .withVersion(KubeVirtConfiguration.KUBEVIRT_API_VERSION)
            .withPlural("virtualmachines")
            .withScope("Namespaced")
            .build();

    private static final CustomResourceDefinitionContext VMI_CONTEXT = new CustomResourceDefinitionContext.Builder()
            .withGroup(KubeVirtConfiguration.KUBEVIRT_API_GROUP)
            .withVersion(KubeVirtConfiguration.KUBEVIRT_API_VERSION)
            .withPlural("virtualmachineinstances")
            .withScope("Namespaced")
            .build();

    private static final CustomResourceDefinitionContext DV_CONTEXT = new CustomResourceDefinitionContext.Builder()
            .withGroup(KubeVirtConfiguration.CDI_API_GROUP)
            .withVersion(KubeVirtConfiguration.CDI_API_VERSION)
            .withPlural("datavolumes")
            .withScope("Namespaced")
            .build();

    /** Suffix appended to the VM name to derive the cloud-init Secret name. */
    private static final String CLOUDINIT_SECRET_SUFFIX = "-cloudinit";

    private static final CustomResourceDefinitionContext DS_CONTEXT = new CustomResourceDefinitionContext.Builder()
            .withGroup(KubeVirtConfiguration.CDI_API_GROUP)
            .withVersion(KubeVirtConfiguration.CDI_API_VERSION)
            .withPlural("datasources")
            .withScope("Namespaced")
            .build();

    private final KubernetesClient client;
    private final String namespace;

    public KubeVirt(String serverUrl, String token, String namespace, boolean ignoreSsl) {
        this(serverUrl, token, namespace, ignoreSsl, 0, 0);
    }

    /**
     * Creates a KubeVirt client with custom connection and request timeouts.
     *
     * @param serverUrl          The Kubernetes API server URL
     * @param token              The authentication token
     * @param namespace          The Kubernetes namespace
     * @param ignoreSsl          Whether to ignore SSL certificate errors
     * @param connectionTimeoutMs Connection timeout in milliseconds (0 = Fabric8 default)
     * @param requestTimeoutMs   Request timeout in milliseconds (0 = Fabric8 default)
     */
    public KubeVirt(String serverUrl, String token, String namespace, boolean ignoreSsl,
                    int connectionTimeoutMs, int requestTimeoutMs) {
        ConfigBuilder configBuilder = new ConfigBuilder()
                .withMasterUrl(serverUrl)
                .withOauthToken(token)
                .withNamespace(namespace)
                .withTrustCerts(ignoreSsl);
        if (connectionTimeoutMs > 0) {
            configBuilder.withConnectionTimeout(connectionTimeoutMs);
        }
        if (requestTimeoutMs > 0) {
            configBuilder.withRequestTimeout(requestTimeoutMs);
        }
        Config config = configBuilder.build();
        this.client = new KubernetesClientBuilder().withConfig(config).build();
        this.namespace = namespace;
    }

    public KubernetesClient getClient() {
        return client;
    }

    public String getNamespace() {
        return namespace;
    }

    /**
     * Verifies basic connectivity to the Kubernetes cluster.
     * This is a lightweight check that only fetches the server version.
     *
     * @throws Exception if the cluster is unreachable or not responding
     */
    public void verifyConnection() throws Exception {
        try {
            String serverVersion = client.getKubernetesVersion().getGitVersion();
            LOGGER.log(Level.FINE, "Cluster connectivity verified. Version: {0}", serverVersion);
        } catch (Exception e) {
            throw new Exception("Kubernetes cluster is not reachable at " + client.getMasterUrl()
                    + ": " + e.getMessage(), e);
        }
    }

    /**
     * Checks if a DataSource exists and is ready for cloning.
     * 
     * @param dataSourceNamespace Namespace of the DataSource
     * @param dataSourceName Name of the DataSource
     * @return A status object indicating if the DataSource exists and is ready
     */
    @SuppressWarnings("unchecked")
    public DataSourceStatus checkDataSource(String dataSourceNamespace, String dataSourceName) {
        try {
            GenericKubernetesResource ds = client.genericKubernetesResources(DS_CONTEXT)
                    .inNamespace(dataSourceNamespace)
                    .withName(dataSourceName)
                    .get();

            if (ds == null) {
                return new DataSourceStatus(false, false, "DataSource not found");
            }

            Map<String, Object> status = (Map<String, Object>) ds.getAdditionalProperties().get("status");
            if (status == null) {
                return new DataSourceStatus(true, false, "DataSource exists but has no status yet");
            }

            // Check if DataSource is ready
            List<Map<String, Object>> conditions = (List<Map<String, Object>>) status.get("conditions");
            boolean ready = false;
            String message = "DataSource exists";
            
            if (conditions != null) {
                for (Map<String, Object> condition : conditions) {
                    String type = (String) condition.get("type");
                    String condStatus = (String) condition.get("status");
                    if ("Ready".equals(type) && "True".equals(condStatus)) {
                        ready = true;
                        message = "DataSource is ready for cloning";
                        break;
                    }
                }
            }

            return new DataSourceStatus(true, ready, message);
            
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Error checking DataSource " + dataSourceNamespace + "/" + dataSourceName, e);
            return new DataSourceStatus(false, false, "Error checking DataSource: " + e.getMessage());
        }
    }

    /**
     * Creates a DataSource from a container image registry URL.
     * First creates a DataVolume to import the image, then creates a DataSource
     * referencing it.
     * 
     * @param dataSourceNamespace Namespace where to create the DataSource
     * @param dataSourceName      Name of the DataSource
     * @param imageUrl            Container image URL (e.g.,
     *                            "quay.io/containerdisks/fedora:40")
     * @param diskSize            Size of the disk (e.g., "30Gi")
     * @param accessMode          PVC access mode (e.g., "ReadWriteOnce" or "ReadWriteMany")
     * @param callback            Callback for progress reporting
     */
    @SuppressFBWarnings("REC_CATCH_EXCEPTION")
    public void createDataSource(String dataSourceNamespace, String dataSourceName, String imageUrl,
            String diskSize, String accessMode, ProvisioningCallback callback) {
        callback.log("Creating DataSource: " + dataSourceName + " in namespace: " + dataSourceNamespace);
        callback.log("Source image: " + imageUrl);
        LOGGER.log(Level.FINE, "Creating DataSource {0} in namespace {1} from image {2}",
                new Object[] { dataSourceName, dataSourceNamespace, imageUrl });

        // Ensure the URL has the docker:// prefix, but avoid duplication
        String registryUrl = imageUrl;
        if (!registryUrl.startsWith("docker://")) {
            registryUrl = "docker://" + registryUrl;
        }

        // Step 1: Create a DataVolume to import the image
        String dvName = dataSourceName + "-import";
        callback.log("Step 1: Creating DataVolume to import image: " + dvName);

        Map<String, Object> dvSpec = new LinkedHashMap<>();
        Map<String, Object> dvSource = new LinkedHashMap<>();
        Map<String, Object> registry = new LinkedHashMap<>();
        registry.put("url", registryUrl);
        dvSource.put("registry", registry);
        dvSpec.put("source", dvSource);

        Map<String, Object> storage = new LinkedHashMap<>();
        storage.put("accessModes", List.of(accessMode != null ? accessMode : "ReadWriteMany"));
        Map<String, Object> storageResources = new LinkedHashMap<>();
        Map<String, Object> storageRequests = new LinkedHashMap<>();
        storageRequests.put("storage", diskSize);
        storageResources.put("requests", storageRequests);
        storage.put("resources", storageResources);
        dvSpec.put("storage", storage);

        GenericKubernetesResource dv = new GenericKubernetesResourceBuilder()
                .withApiVersion(KubeVirtConfiguration.CDI_API_GROUP + "/" + KubeVirtConfiguration.CDI_API_VERSION)
                .withKind("DataVolume")
                .withNewMetadata()
                .withName(dvName)
                .withNamespace(dataSourceNamespace)
                .addToAnnotations("cdi.kubevirt.io/storage.bind.immediate.requested", "true")
                .endMetadata()
                .build();
        dv.setAdditionalProperty("spec", dvSpec);

        // Step 1: Create the DataVolume (or discover a concurrent creation)
        try {
            executeWithQuotaConflictRetry(
                    "DataVolume " + dataSourceNamespace + "/" + dvName,
                    callback,
                    () -> client.genericKubernetesResources(DV_CONTEXT)
                            .inNamespace(dataSourceNamespace)
                            .resource(dv)
                            .create());
            callback.log("DataVolume created. Waiting for import to complete...");
            LOGGER.log(Level.FINE, "DataVolume {0} created for DataSource import", dvName);
        } catch (KubernetesClientException e) {
            if (e.getCode() == 409
                    && !KubernetesClientExceptions.isResourceQuotaUpdateConflict(e.getCode(), e.getMessage())) {
                // Another thread or controller is already importing this image
                callback.log("DataVolume already exists (concurrent import detected). Waiting for it to complete...");
                LOGGER.log(Level.FINE, "DataVolume {0} already exists, joining existing import", dvName);
            } else {
                throw e;
            }
        }

        try {
            // Step 2: Wait for DataVolume to complete
            int maxWaitSeconds = 600;   // 10 minutes for import
            int checkIntervalSeconds = 5;
            int maxAttempts = maxWaitSeconds / checkIntervalSeconds;

            boolean importComplete = false;
            for (int attempt = 0; attempt < maxAttempts; attempt++) {
                DataVolumeStatus dvStatus = getDataVolumeStatus(dvName, dataSourceNamespace);

                if (dvStatus.isComplete()) {
                    callback.log("DataVolume import completed successfully!");
                    importComplete = true;
                    break;
                } else if (dvStatus.isFailed()) {
                    throw new RuntimeException("DataVolume import failed: " + dvStatus.getMessage());
                }

                if (attempt % 6 == 0) { // Log every 30 seconds
                    callback.log("Waiting for DataVolume import... Status: " + dvStatus.toString());
                }

                if (attempt < maxAttempts - 1) {
                    try {
                        Thread.sleep(checkIntervalSeconds * 1000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted while waiting for DataVolume import", e);
                    }
                }
            }

            if (!importComplete) {
                throw new RuntimeException("DataVolume import did not complete within timeout");
            }

            // Step 3: Create DataSource that references the DataVolume's PVC
            // The PVC name is the same as the DataVolume name
            callback.log("Step 2: Creating DataSource referencing imported PVC...");

            Map<String, Object> dsSpec = new LinkedHashMap<>();
            Map<String, Object> dsSource = new LinkedHashMap<>();
            Map<String, Object> pvc = new LinkedHashMap<>();
            pvc.put("name", dvName); // PVC name matches DataVolume name
            pvc.put("namespace", dataSourceNamespace);
            dsSource.put("pvc", pvc);
            dsSpec.put("source", dsSource);

            GenericKubernetesResource ds = new GenericKubernetesResourceBuilder()
                    .withApiVersion(KubeVirtConfiguration.CDI_API_GROUP + "/" + KubeVirtConfiguration.CDI_API_VERSION)
                    .withKind("DataSource")
                    .withNewMetadata()
                    .withName(dataSourceName)
                    .withNamespace(dataSourceNamespace)
                    .endMetadata()
                    .build();
            ds.setAdditionalProperty("spec", dsSpec);

            try {
                executeWithQuotaConflictRetry(
                        "DataSource " + dataSourceNamespace + "/" + dataSourceName,
                        callback,
                        () -> client.genericKubernetesResources(DS_CONTEXT)
                                .inNamespace(dataSourceNamespace)
                                .resource(ds)
                                .create());
                callback.log("DataSource created successfully. Waiting for it to become ready...");
                LOGGER.log(Level.FINE, "DataSource {0} created successfully", dataSourceName);
            } catch (KubernetesClientException e) {
                if (e.getCode() == 409
                        && !KubernetesClientExceptions.isResourceQuotaUpdateConflict(e.getCode(), e.getMessage())) {
                    callback.log("DataSource already exists (concurrent creation). Will wait for it to become ready...");
                    LOGGER.log(Level.FINE, "DataSource {0} already exists, skipping creation", dataSourceName);
                } else {
                    throw e;
                }
            }

        } catch (Exception e) {
            String errorMsg = "Failed to create DataSource: " + e.getMessage();
            callback.log("ERROR: " + errorMsg);
            LOGGER.log(Level.SEVERE, errorMsg, e);
            throw new RuntimeException(errorMsg, e);
        }
    }

    /**
     * Waits for a DataSource to become ready with a timeout.
     * 
     * @param dataSourceNamespace Namespace of the DataSource
     * @param dataSourceName      Name of the DataSource
     * @param timeoutSeconds      Maximum time to wait in seconds
     * @param callback            Callback for progress reporting
     * @return true if DataSource became ready, false if timeout
     */
    public boolean waitForDataSourceReady(String dataSourceNamespace, String dataSourceName,
            int timeoutSeconds, ProvisioningCallback callback) {
        final int checkIntervalSeconds = 5;
        final int maxAttempts = timeoutSeconds / checkIntervalSeconds;

        callback.log("Waiting for DataSource to become ready (timeout: " + timeoutSeconds + " seconds)...");

        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            DataSourceStatus status = checkDataSource(dataSourceNamespace, dataSourceName);

            if (!status.exists()) {
                callback.log("DataSource not found - may still be creating...");
            } else if (status.isReady()) {
                callback.log("DataSource is ready!");
                return true;
            } else {
                if (attempt % 6 == 0) { // Log every 30 seconds
                    callback.log("DataSource exists but not ready yet: " + status.getMessage());
                }
            }

            if (attempt < maxAttempts - 1) {
                try {
                    Thread.sleep(checkIntervalSeconds * 1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    callback.log("Interrupted while waiting for DataSource");
                    return false;
                }
            }
        }

        DataSourceStatus finalStatus = checkDataSource(dataSourceNamespace, dataSourceName);
        callback.log("ERROR: DataSource did not become ready within timeout");
        callback.log("Final status: " + finalStatus.getMessage());
        return false;
    }

    /**
     * Status information for a DataSource.
     */
    public static class DataSourceStatus {
        private final boolean exists;
        private final boolean ready;
        private final String message;

        public DataSourceStatus(boolean exists, boolean ready, String message) {
            this.exists = exists;
            this.ready = ready;
            this.message = message;
        }

        public boolean exists() {
            return exists;
        }

        public boolean isReady() {
            return ready;
        }

        public String getMessage() {
            return message;
        }

        @Override
        public String toString() {
            return message + " (exists: " + exists + ", ready: " + ready + ")";
        }
    }

    /**
     * Creates a KubeVirt VirtualMachine with a ContainerDisk image.
     * Uses a DataVolumeTemplate to import the container image into a PVC with configurable size.
     * 
     * @param name VM name
     * @param cloudName The cloud name for labeling
     * @param templateName The template name for labeling
     * @param image Container disk image (registry URL)
     * @param diskSize Size of the disk (e.g., "30Gi")
     * @param accessMode PVC access mode (ReadWriteOnce or ReadWriteMany)
     * @param cpu CPU requests (reserved)
     * @param memory Memory requests (reserved)
     * @param cpuLimit CPU limit (maximum), may be null
     * @param memoryLimit Memory limit (maximum), may be null
     * @param userData Cloud-init user data
     * @param injectTmpDiskConfig Whether to inject /tmp disk configuration into cloud-init
     * @param callback Callback for progress reporting
     */
    public void createVM(String name, String cloudName, String templateName, String image, String diskSize, String accessMode, 
                         String cpu, String memory, String cpuLimit, String memoryLimit,
                         String userData, boolean injectTmpDiskConfig, ProvisioningCallback callback) {
        callback.log("Initializing VM creation with ContainerDisk...");
        if (injectTmpDiskConfig) {
            callback.log("Ephemeral /tmp disk injection enabled");
        }
        LOGGER.log(Level.FINE, "Creating VirtualMachine with ContainerDisk: {0} in namespace: {1}", new Object[]{name, namespace});

        String diskName = name + "-disk";
        Map<String, Object> spec = buildBaseVMSpec(name, cpu, memory, cpuLimit, memoryLimit, userData, "rootdisk", injectTmpDiskConfig);

        // DataVolumeTemplate for importing from container registry
        Map<String, Object> dataVolumeTemplate = new HashMap<>();
        Map<String, Object> dvMetadata = new HashMap<>();
        dvMetadata.put("name", diskName);
        dataVolumeTemplate.put("metadata", dvMetadata);

        Map<String, Object> dvSpec = new HashMap<>();
        // Use registry source to import from container disk image
        Map<String, Object> source = new HashMap<>();
        source.put("registry", Map.of("url", "docker://" + image));
        dvSpec.put("source", source);

        Map<String, Object> storage = new HashMap<>();
        storage.put("accessModes", List.of(accessMode != null ? accessMode : "ReadWriteMany"));
        Map<String, Object> storageResources = new HashMap<>();
        Map<String, Object> storageRequests = new HashMap<>();
        storageRequests.put("storage", diskSize);
        storageResources.put("requests", storageRequests);
        storage.put("resources", storageResources);
        dvSpec.put("storage", storage);

        dataVolumeTemplate.put("spec", dvSpec);
        spec.put("dataVolumeTemplates", List.of(dataVolumeTemplate));

        // DataVolume reference in volumes
        Map<String, Object> rootDiskVolume = new HashMap<>();
        rootDiskVolume.put("name", "rootdisk");
        rootDiskVolume.put("dataVolume", Map.of("name", diskName));

        finalizeAndSubmitVM(name, cloudName, templateName, spec, rootDiskVolume,
                userData, injectTmpDiskConfig,
                "Submitting VM resource to cluster (disk import will start automatically)...", callback);
    }

    /**
     * Creates a KubeVirt VirtualMachine by cloning from a DataSource.
     * 
     * @param name VM name
     * @param cloudName The cloud name for labeling
     * @param templateName The template name for labeling
     * @param dataSourceNamespace Namespace of the DataSource
     * @param dataSourceName Name of the DataSource
     * @param diskSize Size of the cloned disk
     * @param accessMode PVC access mode (ReadWriteOnce or ReadWriteMany)
     * @param cpu CPU requests (reserved)
     * @param memory Memory requests (reserved)
     * @param cpuLimit CPU limit (maximum), may be null
     * @param memoryLimit Memory limit (maximum), may be null
     * @param userData Cloud-init user data
     * @param injectTmpDiskConfig Whether to inject /tmp disk configuration into cloud-init
     * @param callback Callback for progress reporting
     */
    public void createVMFromDataSource(String name, String cloudName, String templateName, String dataSourceNamespace, String dataSourceName,
                                       String diskSize, String accessMode, 
                                       String cpu, String memory, String cpuLimit, String memoryLimit,
                                       String userData, boolean injectTmpDiskConfig, ProvisioningCallback callback) {
        callback.log("Initializing VM creation from DataSource...");
        callback.log("Cloning from: " + dataSourceNamespace + "/" + dataSourceName);
        if (injectTmpDiskConfig) {
            callback.log("Ephemeral /tmp disk injection enabled");
        }
        LOGGER.log(Level.FINE, "Creating VirtualMachine from DataSource: {0} in namespace: {1}", new Object[]{name, namespace});

        String diskName = name + "-disk";
        Map<String, Object> spec = buildBaseVMSpec(name, cpu, memory, cpuLimit, memoryLimit, userData, "rootdisk", injectTmpDiskConfig);

        // DataVolumeTemplate for cloning from DataSource
        Map<String, Object> dataVolumeTemplate = new HashMap<>();
        Map<String, Object> dvMetadata = new HashMap<>();
        dvMetadata.put("name", diskName);
        dataVolumeTemplate.put("metadata", dvMetadata);

        Map<String, Object> dvSpec = new HashMap<>();
        Map<String, Object> sourceRef = new HashMap<>();
        sourceRef.put("kind", "DataSource");
        sourceRef.put("namespace", dataSourceNamespace);
        sourceRef.put("name", dataSourceName);
        dvSpec.put("sourceRef", sourceRef);

        Map<String, Object> storage = new HashMap<>();
        storage.put("accessModes", List.of(accessMode != null ? accessMode : "ReadWriteMany"));
        Map<String, Object> storageResources = new HashMap<>();
        Map<String, Object> storageRequests = new HashMap<>();
        storageRequests.put("storage", diskSize);
        storageResources.put("requests", storageRequests);
        storage.put("resources", storageResources);
        dvSpec.put("storage", storage);

        dataVolumeTemplate.put("spec", dvSpec);
        spec.put("dataVolumeTemplates", List.of(dataVolumeTemplate));

        // DataVolume reference in volumes
        Map<String, Object> rootDiskVolume = new HashMap<>();
        rootDiskVolume.put("name", "rootdisk");
        rootDiskVolume.put("dataVolume", Map.of("name", diskName));

        finalizeAndSubmitVM(name, cloudName, templateName, spec, rootDiskVolume,
                userData, injectTmpDiskConfig,
                "Submitting VM resource to cluster (disk cloning will start automatically)...", callback);
    }

    /**
     * Creates a KubeVirt VirtualMachine by importing an image into a DataSource and then cloning from it.
     * If a DataSource with the given name already exists and is ready, it is reused (cached).
     * Otherwise, a new DataVolume is created to import the image, a DataSource is created referencing it,
     * and the VM clones from that DataSource.
     * 
     * @param name VM name
     * @param cloudName The cloud name for labeling
     * @param templateName The template name for labeling
     * @param dataSourceNamespace Namespace where the DataSource will be created or looked up
     * @param dataSourceName Name of the DataSource to create or reuse
     * @param image Container disk image (registry URL)
     * @param diskSize Size of the disk (e.g., "30Gi")
     * @param accessMode PVC access mode (ReadWriteOnce or ReadWriteMany)
     * @param cpu CPU requests (reserved)
     * @param memory Memory requests (reserved)
     * @param cpuLimit CPU limit (maximum), may be null
     * @param memoryLimit Memory limit (maximum), may be null
     * @param userData Cloud-init user data
     * @param injectTmpDiskConfig Whether to inject /tmp disk configuration into cloud-init
     * @param callback Callback for progress reporting
     */
    public void createVMFromDataSourceImport(String name, String cloudName, String templateName,
                                              String dataSourceNamespace, String dataSourceName,
                                              String image, String diskSize, String accessMode,
                                              String cpu, String memory, String cpuLimit, String memoryLimit,
                                              String userData, boolean injectTmpDiskConfig, ProvisioningCallback callback) {
        callback.log("Initializing VM creation with DataSource Import...");
        if (injectTmpDiskConfig) {
            callback.log("Ephemeral /tmp disk injection enabled");
        }
        LOGGER.log(Level.FINE, "Creating VirtualMachine with DataSource Import: {0} in namespace: {1}", new Object[]{name, namespace});

        callback.log("Using DataSource caching for image: " + image);
        callback.log("DataSource name: " + dataSourceName + " in namespace: " + dataSourceNamespace);

        // Check if DataSource exists
        DataSourceStatus dsStatus = checkDataSource(dataSourceNamespace, dataSourceName);

        if (!dsStatus.exists()) {
            // DataSource doesn't exist - create it
            callback.log("DataSource not found. Creating new DataSource to cache the image...");
            createDataSource(dataSourceNamespace, dataSourceName, image, diskSize, accessMode, callback);

            // Wait for DataSource to become ready (5 minutes timeout)
            boolean ready = waitForDataSourceReady(dataSourceNamespace, dataSourceName, 300, callback);
            if (!ready) {
                String errorMsg = "DataSource '" + dataSourceName + "' did not become ready within timeout. " +
                        "The image may be too large or the registry may be slow.";
                callback.log("ERROR: " + errorMsg);
                throw new RuntimeException(errorMsg);
            }
            callback.log("DataSource created and ready. Using cached image for faster provisioning.");
        } else if (!dsStatus.isReady()) {
            // DataSource exists but not ready - wait for it
            callback.log("DataSource exists but not ready: " + dsStatus.getMessage());
            callback.log("Waiting for DataSource to become ready...");

            boolean ready = waitForDataSourceReady(dataSourceNamespace, dataSourceName, 300, callback);
            if (!ready) {
                String errorMsg = "DataSource '" + dataSourceName + "' did not become ready within timeout. " +
                        "Current status: " + dsStatus.getMessage();
                callback.log("ERROR: " + errorMsg);
                throw new RuntimeException(errorMsg);
            }
        } else {
            callback.log("Using existing cached DataSource. Image will not be downloaded again.");
        }

        // Now create VM using DataSource cloning
        String diskName = name + "-disk";
        Map<String, Object> spec = buildBaseVMSpec(name, cpu, memory, cpuLimit, memoryLimit, userData, "rootdisk", injectTmpDiskConfig);

        // DataVolumeTemplate for cloning from DataSource
        Map<String, Object> dataVolumeTemplate = new HashMap<>();
        Map<String, Object> dvMetadata = new HashMap<>();
        dvMetadata.put("name", diskName);
        dataVolumeTemplate.put("metadata", dvMetadata);

        Map<String, Object> dvSpec = new HashMap<>();
        Map<String, Object> sourceRef = new HashMap<>();
        sourceRef.put("kind", "DataSource");
        sourceRef.put("namespace", dataSourceNamespace);
        sourceRef.put("name", dataSourceName);
        dvSpec.put("sourceRef", sourceRef);

        Map<String, Object> storage = new HashMap<>();
        storage.put("accessModes", List.of(accessMode != null ? accessMode : "ReadWriteMany"));
        Map<String, Object> storageResources = new HashMap<>();
        Map<String, Object> storageRequests = new HashMap<>();
        storageRequests.put("storage", diskSize);
        storageResources.put("requests", storageRequests);
        storage.put("resources", storageResources);
        dvSpec.put("storage", storage);

        dataVolumeTemplate.put("spec", dvSpec);
        spec.put("dataVolumeTemplates", List.of(dataVolumeTemplate));

        // DataVolume reference in volumes
        Map<String, Object> rootDiskVolume = new HashMap<>();
        rootDiskVolume.put("name", "rootdisk");
        rootDiskVolume.put("dataVolume", Map.of("name", diskName));

        finalizeAndSubmitVM(name, cloudName, templateName, spec, rootDiskVolume,
                userData, injectTmpDiskConfig,
                "Submitting VM resource to cluster (cloning from cached DataSource)...", callback);
    }

    /**
     * Builds the base VM spec structure shared between ContainerDisk and DataSource VMs.
     * Optionally includes a tmp disk for /tmp mount point.
     * 
     * @param name VM name
     * @param cpu CPU requests (reserved)
     * @param memory Memory requests (reserved)
     * @param cpuLimit CPU limit (maximum), may be null or empty
     * @param memoryLimit Memory limit (maximum), may be null or empty
     * @param userData Cloud-init user data
     * @param rootDiskName Name of the root disk
     * @param injectTmpDiskConfig Whether to include the /tmp disk
     */
    private Map<String, Object> buildBaseVMSpec(String name, String cpu, String memory,
                                                 String cpuLimit, String memoryLimit,
                                                 String userData, String rootDiskName, boolean injectTmpDiskConfig) {
        Map<String, Object> spec = new HashMap<>();
        spec.put("runStrategy", "Always");

        // Template spec
        Map<String, Object> templateSpec = new HashMap<>();

        // Domain configuration
        Map<String, Object> domain = new HashMap<>();
        Map<String, Object> resources = new HashMap<>();
        
        // Requests (reserved resources)
        Map<String, Object> requests = new HashMap<>();
        requests.put("cpu", cpu);
        requests.put("memory", memory);
        resources.put("requests", requests);
        
        // Limits (maximum resources) - only add if specified
        boolean hasLimits = (cpuLimit != null && !cpuLimit.trim().isEmpty()) 
                         || (memoryLimit != null && !memoryLimit.trim().isEmpty());
        if (hasLimits) {
            Map<String, Object> limits = new HashMap<>();
            if (cpuLimit != null && !cpuLimit.trim().isEmpty()) {
                limits.put("cpu", cpuLimit.trim());
            }
            if (memoryLimit != null && !memoryLimit.trim().isEmpty()) {
                limits.put("memory", memoryLimit.trim());
            }
            resources.put("limits", limits);
        }
        
        domain.put("resources", resources);

        // Devices - disks (root, cloudinit, and optionally tmp)
        Map<String, Object> disk1 = new HashMap<>();
        disk1.put("name", rootDiskName);
        disk1.put("disk", Map.of("bus", "virtio"));

        Map<String, Object> disk2 = new HashMap<>();
        disk2.put("name", "cloudinit");
        disk2.put("disk", Map.of("bus", "virtio"));

        // Network interface - use masquerade mode for pod network access.
        // Masquerade mode requires explicit port definitions for iptables DNAT rules
        // inside the virt-launcher pod. Without listing port 22, the NAT won't forward
        // SSH connections from the pod IP to the VM's internal IP, causing
        // "no route to host" errors from virt-api's portforward subresource.
        Map<String, Object> networkInterface = new HashMap<>();
        networkInterface.put("name", "default");
        networkInterface.put("masquerade", Map.of());
        networkInterface.put("ports", List.of(
                Map.of("name", "ssh", "port", 22, "protocol", "TCP")
        ));

        Map<String, Object> devices = new HashMap<>();
        if (injectTmpDiskConfig) {
            Map<String, Object> disk3 = new HashMap<>();
            disk3.put("name", "tmpdisk");
            disk3.put("disk", Map.of("bus", "virtio"));
            devices.put("disks", List.of(disk1, disk2, disk3));
        } else {
            devices.put("disks", List.of(disk1, disk2));
        }
        devices.put("interfaces", List.of(networkInterface));
        domain.put("devices", devices);
        templateSpec.put("domain", domain);

        // Pod network configuration
        Map<String, Object> podNetwork = new HashMap<>();
        podNetwork.put("name", "default");
        podNetwork.put("pod", Map.of());
        templateSpec.put("networks", List.of(podNetwork));

        // Template metadata
        Map<String, Object> templateMetadata = new HashMap<>();
        templateMetadata.put("labels", Map.of("kubevirt.io/vm", name));

        Map<String, Object> template = new HashMap<>();
        template.put("metadata", templateMetadata);
        template.put("spec", templateSpec);
        spec.put("template", template);

        return spec;
    }

    /**
     * Creates a Kubernetes Secret containing the cloud-init user data.
     * The secret is named "{vmName}-cloudinit" following the existing naming convention
     * (e.g., "{vmName}-disk" for DataVolumes).
     * <p>
     * This avoids KubeVirt's 2KB inline userdata limit by using secretRef instead.
     *
     * @param vmName   The VM name (used to derive the secret name)
     * @param userData The final cloud-init user data content
     * @param callback Callback for progress reporting
     * @return The name of the created secret
     */
    private String createCloudInitSecret(String vmName, String userData, ProvisioningCallback callback) {
        String secretName = vmName + CLOUDINIT_SECRET_SUFFIX;
        LOGGER.log(Level.FINE, "Creating cloud-init Secret: {0} in namespace: {1}", new Object[]{secretName, namespace});

        Secret secret = new SecretBuilder()
                .withNewMetadata()
                    .withName(secretName)
                    .withNamespace(namespace)
                .endMetadata()
                .withType("Opaque")
                .withStringData(Map.of("userdata", userData))
                .build();

        executeWithQuotaConflictRetry(
                "Secret " + namespace + "/" + secretName,
                callback,
                () -> client.secrets().inNamespace(namespace).resource(secret).serverSideApply());
        callback.log("Created cloud-init Secret: " + secretName);
        return secretName;
    }

    /**
     * Deletes the cloud-init Secret associated with a VM.
     * This is a best-effort operation — if the secret doesn't exist
     * (e.g., VM was created without cloud-init, or with an older plugin version),
     * the error is silently ignored.
     *
     * @param vmName The VM name (used to derive the secret name)
     */
    private void deleteCloudInitSecret(String vmName) {
        String secretName = vmName + CLOUDINIT_SECRET_SUFFIX;
        try {
            client.secrets().inNamespace(namespace).withName(secretName).delete();
            LOGGER.log(Level.FINE, "Deleted cloud-init Secret: {0}", secretName);
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Cloud-init secret not found or already deleted: " + secretName, e);
        }
    }

    /**
     * Sets an ownerReference on the cloud-init Secret pointing to the VirtualMachine.
     * This ensures Kubernetes garbage-collects the Secret when the VM is deleted
     * (e.g., via {@code kubectl delete vm}), acting as a safety net alongside the
     * explicit {@link #deleteCloudInitSecret(String)} cleanup in {@link #deleteVM(String, String, String)}.
     * <p>
     * This operation is best-effort: if the patch fails (e.g., due to RBAC restrictions
     * on patching secrets), provisioning continues normally and the explicit cleanup
     * in {@link #deleteVM(String, String, String)} remains the primary mechanism.
     *
     * @param secretName The name of the cloud-init Secret
     * @param vmName     The VirtualMachine name (owner)
     * @param vmUid      The VirtualMachine UID (from the API server response)
     */
    private void setSecretOwnerReference(String secretName, String vmName, String vmUid) {
        try {
            Secret secret = client.secrets().inNamespace(namespace).withName(secretName).get();
            if (secret == null) {
                LOGGER.log(Level.WARNING, "Cannot set ownerReference: Secret {0} not found", secretName);
                return;
            }

            OwnerReference ownerRef = new OwnerReferenceBuilder()
                    .withApiVersion(KubeVirtConfiguration.KUBEVIRT_API_GROUP + "/" + KubeVirtConfiguration.KUBEVIRT_API_VERSION)
                    .withKind("VirtualMachine")
                    .withName(vmName)
                    .withUid(vmUid)
                    .withBlockOwnerDeletion(false)
                    .build();

            secret.getMetadata().setOwnerReferences(List.of(ownerRef));
            client.secrets().inNamespace(namespace).resource(secret).update();
            LOGGER.log(Level.FINE, "Set ownerReference on Secret {0} -> VM {1} (uid={2})",
                    new Object[]{secretName, vmName, vmUid});
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to set ownerReference on Secret " + secretName +
                    " (non-fatal, explicit cleanup still active): " + e.getMessage(), e);
        }
    }

    /**
     * Computes the final cloud-init user data, optionally injecting /tmp disk mount commands.
     *
     * @param userData            Raw cloud-init user data from configuration
     * @param injectTmpDiskConfig Whether to inject /tmp disk mount commands
     * @return The final user data string ready to be stored in a Secret
     */
    private String buildFinalUserData(String userData, boolean injectTmpDiskConfig) {
        if (injectTmpDiskConfig) {
            // Commands to format and mount the emptyDisk at /tmp
            // The tmpdisk appears as /dev/vdc (after rootdisk=/dev/vda and cloudinit=/dev/vdb)
            String tmpMountCommands = 
                "# Format and mount /tmp disk\n" +
                "runcmd:\n" +
                "  - |\n" +
                "    # Find the emptyDisk (typically /dev/vdc) and mount at /tmp\n" +
                "    TMP_DISK=/dev/vdc\n" +
                "    if [ -b \"$TMP_DISK\" ]; then\n" +
                "      # Create filesystem if not already present\n" +
                "      if ! blkid \"$TMP_DISK\" | grep -q ext4; then\n" +
                "        mkfs.ext4 -F \"$TMP_DISK\"\n" +
                "      fi\n" +
                "      # Create mount point and mount\n" +
                "      mkdir -p /mnt/tmp\n" +
                "      mount \"$TMP_DISK\" /mnt/tmp\n" +
                "      # Move existing /tmp contents and remount\n" +
                "      rsync -a /tmp/ /mnt/tmp/ 2>/dev/null || true\n" +
                "      umount /mnt/tmp\n" +
                "      mount \"$TMP_DISK\" /tmp\n" +
                "      chmod 1777 /tmp\n" +
                "      echo 'Successfully mounted /tmp disk'\n" +
                "    else\n" +
                "      echo 'Warning: /tmp disk not found at $TMP_DISK'\n" +
                "    fi\n";
            
            // The script to inject into existing runcmd sections
            String tmpMountScript = 
                "  - |\n" +
                "    TMP_DISK=/dev/vdc\n" +
                "    if [ -b \"$TMP_DISK\" ]; then\n" +
                "      if ! blkid \"$TMP_DISK\" | grep -q ext4; then mkfs.ext4 -F \"$TMP_DISK\"; fi\n" +
                "      mkdir -p /mnt/tmp && mount \"$TMP_DISK\" /mnt/tmp\n" +
                "      rsync -a /tmp/ /mnt/tmp/ 2>/dev/null || true\n" +
                "      umount /mnt/tmp && mount \"$TMP_DISK\" /tmp && chmod 1777 /tmp\n" +
                "    fi\n";
            
            if (userData != null && !userData.isEmpty()) {
                // Merge with existing cloud-init - append runcmd if not already present
                if (userData.contains("runcmd:")) {
                    // User already has runcmd section - insert our commands after it
                    // Use Matcher.quoteReplacement to escape $ characters in the script
                    return userData.replaceFirst(
                        "(runcmd:\\s*\\n)",
                        "$1" + Matcher.quoteReplacement(tmpMountScript)
                    );
                } else {
                    // Append runcmd section
                    return userData + "\n" + tmpMountCommands;
                }
            } else {
                return "#cloud-config\n" + tmpMountCommands;
            }
        } else {
            // No /tmp disk injection - use userData as-is or provide minimal cloud-config
            if (userData != null && !userData.isEmpty()) {
                return userData;
            } else {
                return "#cloud-config\n";
            }
        }
    }

    /**
     * Builds the cloud-init volume configuration referencing a Kubernetes Secret.
     * The Secret must already exist and contain the user data under the "userdata" key.
     *
     * @param secretName The name of the Kubernetes Secret containing the cloud-init data
     * @return The volume map for inclusion in the VM spec
     */
    private Map<String, Object> buildCloudInitVolume(String secretName) {
        Map<String, Object> volume = new HashMap<>();
        volume.put("name", "cloudinit");
        volume.put("cloudInitNoCloud", Map.of(
                "secretRef", Map.of("name", secretName)
        ));
        return volume;
    }

    /**
     * Configures cloud-init volumes on the VM spec, creates the cloud-init Secret,
     * submits the VM resource to the cluster, and sets up the owner reference for
     * automatic garbage collection.
     * <p>
     * This method consolidates the common tail logic shared by all {@code createVM*} methods:
     * <ol>
     *   <li>Builds the final user data (optionally injecting /tmp disk mount commands)</li>
     *   <li>Creates a Kubernetes Secret holding the user data</li>
     *   <li>Assembles the volume list (rootdisk + cloudinit + optional tmpdisk)</li>
     *   <li>Creates the VirtualMachine resource</li>
     *   <li>Sets an ownerReference on the Secret so it is garbage-collected with the VM</li>
     * </ol>
     * If VM creation fails, the cloud-init Secret is cleaned up to prevent orphans.
     *
     * @param name               VM name
     * @param cloudName          Cloud name for labeling
     * @param templateName       Template name for labeling
     * @param spec               The base VM spec (already populated with dataVolumeTemplates)
     * @param rootDiskVolume     The root disk volume map (already built by the caller)
     * @param userData           Raw cloud-init user data from configuration
     * @param injectTmpDiskConfig Whether to inject /tmp disk mount commands and add the tmpdisk volume
     * @param submitMessage      Log message shown before submitting the VM (varies per creation mode)
     * @param callback           Callback for progress reporting
     */
    private void finalizeAndSubmitVM(String name, String cloudName, String templateName,
                                     Map<String, Object> spec, Map<String, Object> rootDiskVolume,
                                     String userData, boolean injectTmpDiskConfig,
                                     String submitMessage, ProvisioningCallback callback) {
        // Create cloud-init Secret and reference it in the volume
        String finalUserData = buildFinalUserData(userData, injectTmpDiskConfig);
        String secretName = createCloudInitSecret(name, finalUserData, callback);
        Map<String, Object> cloudInitVolume = buildCloudInitVolume(secretName);

        @SuppressWarnings("unchecked")
        Map<String, Object> template = (Map<String, Object>) spec.get("template");
        @SuppressWarnings("unchecked")
        Map<String, Object> templateSpec = (Map<String, Object>) template.get("spec");

        if (injectTmpDiskConfig) {
            // emptyDisk for /tmp - ephemeral storage that persists for VM lifetime
            Map<String, Object> tmpDiskVolume = new HashMap<>();
            tmpDiskVolume.put("name", "tmpdisk");
            tmpDiskVolume.put("emptyDisk", Map.of("capacity", KubeVirtConfiguration.TMP_DISK_SIZE));
            templateSpec.put("volumes", List.of(rootDiskVolume, cloudInitVolume, tmpDiskVolume));
        } else {
            templateSpec.put("volumes", List.of(rootDiskVolume, cloudInitVolume));
        }

        callback.log(submitMessage);
        try {
            String vmUid = createVMResource(name, cloudName, templateName, spec, callback);
            setSecretOwnerReference(secretName, name, vmUid);
        } catch (Exception e) {
            // Clean up the cloud-init secret if VM creation fails to avoid orphaned secrets
            deleteCloudInitSecret(name);
            throw e;
        }
    }

    /**
     * Sanitizes a URL to be a valid Kubernetes label value.
     * Kubernetes label values must:
     * - Be 63 characters or less
     * - Begin and end with an alphanumeric character
     * - Contain only alphanumerics, dashes (-), underscores (_), and dots (.)
     *
     * @param url The URL to sanitize (e.g., Jenkins URL)
     * @return A sanitized string suitable for use as a Kubernetes label value
     */
    static String sanitizeLabelValue(String url) {
        if (url == null || url.isEmpty()) {
            return "";
        }

        // Remove protocol prefix (http://, https://)
        String value = url.replaceFirst("^https?://", "");

        // Remove trailing slashes and whitespace
        value = value.replaceAll("[/\\s]+$", "");

        // Replace any character that's not alphanumeric, dash, underscore, or dot with dash
        value = value.replaceAll("[^a-zA-Z0-9._-]", "-");

        // Collapse multiple consecutive dashes into a single dash
        value = value.replaceAll("-+", "-");

        // Remove leading and trailing dashes/dots/underscores (must start and end with alphanumeric)
        value = value.replaceAll("^[._-]+", "");
        value = value.replaceAll("[._-]+$", "");

        // Truncate to max label length, but ensure we don't end with a non-alphanumeric character
        if (value.length() > KubeVirtConfiguration.MAX_LABEL_VALUE_LENGTH) {
            value = value.substring(0, KubeVirtConfiguration.MAX_LABEL_VALUE_LENGTH);
            // Remove any trailing non-alphanumeric characters after truncation
            value = value.replaceAll("[._-]+$", "");
        }

        return value;
    }

    /**
     * Gets the sanitized Jenkins controller URL for use as a Kubernetes label value.
     *
     * @return The sanitized Jenkins URL, or empty string if not available
     */
    private static String getControllerLabelValue() {
        try {
            Jenkins jenkins = Jenkins.getInstanceOrNull();
            if (jenkins != null) {
                String rootUrl = jenkins.getRootUrl();
                if (rootUrl != null) {
                    return sanitizeLabelValue(rootUrl);
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Unable to get Jenkins root URL for controller label", e);
        }
        return "";
    }

    @FunctionalInterface
    private interface KubernetesCreateAction<T> {
        T execute() throws KubernetesClientException;
    }

    /**
     * Executes a Kubernetes create operation, retrying transient ResourceQuota and
     * OpenShift ClusterResourceQuota update conflicts.
     *
     * @see <a href="https://github.com/jenkinsci/kubernetes-plugin/issues/2843">kubernetes-plugin#2843</a>
     * @see <a href="https://github.com/kubernetes/kubernetes/issues/67761">kubernetes#67761</a>
     */
    private <T> T executeWithQuotaConflictRetry(String resourceDescription, ProvisioningCallback callback,
            KubernetesCreateAction<T> action) throws KubernetesClientException {
        int maxAttempts = KubeVirtConfiguration.RESOURCE_QUOTA_CONFLICT_MAX_ATTEMPTS;
        int intervalSeconds = KubeVirtConfiguration.VM_PROVISIONING_RETRY_INTERVAL_SECONDS;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return action.execute();
            } catch (KubernetesClientException e) {
                if (!KubernetesClientExceptions.isResourceQuotaUpdateConflict(e.getCode(), e.getMessage())
                        || attempt >= maxAttempts) {
                    throw e;
                }
                callback.log(String.format(
                        "WARNING: Unable to create %s because of a transient resource quota update conflict.%n%s%nRetrying...%n",
                        resourceDescription,
                        e.getMessage()));
                LOGGER.log(Level.FINE, "Resource quota update conflict creating {0}, attempt {1}/{2}",
                        new Object[]{resourceDescription, attempt, maxAttempts});
                try {
                    Thread.sleep(intervalSeconds * 1000L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new KubernetesClientException("Interrupted while retrying after resource quota conflict", ie);
                }
            }
        }
        throw new IllegalStateException("Unreachable");
    }

    /**
     * Validates that the given VM belongs to this Jenkins controller, cloud, and template.
     * Fetches the VM from the cluster and checks its ownership labels.
     *
     * @param vmName           The VM name to validate
     * @param expectedCloud    Expected cloud name (null to skip cloud check)
     * @param expectedTemplate Expected template name (null to skip template check)
     * @throws IllegalStateException if the VM doesn't exist, has no labels,
     *         or belongs to a different controller/cloud/template
     */
    private void validateVMOwnership(String vmName, String expectedCloud, String expectedTemplate) {
        GenericKubernetesResource existing = client.genericKubernetesResources(VM_CONTEXT)
                .inNamespace(namespace)
                .withName(vmName)
                .get();
        if (existing == null) {
            throw new IllegalStateException("VM " + vmName + " not found in namespace " + namespace);
        }

        Map<String, String> existingLabels = existing.getMetadata().getLabels();
        if (existingLabels == null) {
            throw new IllegalStateException("VM " + vmName + " exists but has no labels. "
                    + "It may not have been created by this plugin.");
        }

        String expectedController = getControllerLabelValue();
        String existingController = existingLabels.get(KubeVirtConfiguration.CONTROLLER_LABEL_KEY);
        if (!expectedController.isEmpty() && !expectedController.equals(existingController)) {
            throw new IllegalStateException("VM " + vmName + " belongs to a different "
                    + "Jenkins controller (expected: " + expectedController
                    + ", found: " + existingController + ")");
        }

        String sanitizedCloud = sanitizeLabelValue(expectedCloud);
        if (sanitizedCloud != null && !sanitizedCloud.isEmpty()) {
            String existingCloud = existingLabels.get(KubeVirtConfiguration.CLOUD_LABEL_KEY);
            if (!sanitizedCloud.equals(existingCloud)) {
                throw new IllegalStateException("VM " + vmName + " belongs to a different "
                        + "cloud (expected: " + sanitizedCloud + ", found: " + existingCloud + ")");
            }
        }

        String sanitizedTemplate = sanitizeLabelValue(expectedTemplate);
        if (sanitizedTemplate != null && !sanitizedTemplate.isEmpty()) {
            String existingTemplate = existingLabels.get(KubeVirtConfiguration.TEMPLATE_LABEL_KEY);
            if (!sanitizedTemplate.equals(existingTemplate)) {
                throw new IllegalStateException("VM " + vmName + " belongs to a different "
                        + "template (expected: " + sanitizedTemplate + ", found: " + existingTemplate + ")");
            }
        }
    }
    
    /**
     * Creates the VM resource in the cluster.
     *
     * @param name VM name
     * @param cloudName Cloud name for labeling
     * @param templateName Template name for labeling
     * @param spec VM spec
     * @param callback Progress callback
     * @return The UID of the created VirtualMachine resource
     */
    private String createVMResource(String name, String cloudName, String templateName, Map<String, Object> spec, ProvisioningCallback callback) {
        // Build labels map with the controller, cloud, and template labels
        Map<String, String> labels = new HashMap<>();
        String controllerLabel = getControllerLabelValue();
        if (!controllerLabel.isEmpty()) {
            labels.put(KubeVirtConfiguration.CONTROLLER_LABEL_KEY, controllerLabel);
        }
        if (cloudName != null && !cloudName.isEmpty()) {
            labels.put(KubeVirtConfiguration.CLOUD_LABEL_KEY, sanitizeLabelValue(cloudName));
        }
        if (templateName != null && !templateName.isEmpty()) {
            labels.put(KubeVirtConfiguration.TEMPLATE_LABEL_KEY, sanitizeLabelValue(templateName));
        }

        GenericKubernetesResource vm = new GenericKubernetesResourceBuilder()
                .withApiVersion(KubeVirtConfiguration.KUBEVIRT_API_GROUP + "/" + KubeVirtConfiguration.KUBEVIRT_API_VERSION)
                .withKind("VirtualMachine")
                .withNewMetadata()
                    .withName(name)
                    .withNamespace(namespace)
                    .withLabels(labels)
                .endMetadata()
                .build();
        vm.setAdditionalProperty("spec", spec);

        try {
            GenericKubernetesResource created = executeWithQuotaConflictRetry(
                    "VirtualMachine " + namespace + "/" + name,
                    callback,
                    () -> client.genericKubernetesResources(VM_CONTEXT)
                            .inNamespace(namespace)
                            .resource(vm)
                            .create());

            callback.log("VM resource created successfully in namespace: " + namespace);
            LOGGER.log(Level.FINE, "VirtualMachine {0} created successfully", name);
            return created.getMetadata().getUid();
        } catch (KubernetesClientException e) {
            if (e.getCode() == 409
                    && !KubernetesClientExceptions.isResourceQuotaUpdateConflict(e.getCode(), e.getMessage())) {
                callback.log("VM already exists (likely from a previous provisioning attempt). Verifying ownership...");
                LOGGER.log(Level.FINE, "VirtualMachine {0} already exists, verifying ownership", name);

                validateVMOwnership(name, cloudName, templateName);

                GenericKubernetesResource existing = client.genericKubernetesResources(VM_CONTEXT)
                        .inNamespace(namespace)
                        .withName(name)
                        .get();
                if (existing == null) {
                    throw new IllegalStateException("VM " + name + " reported as existing but could not be fetched");
                }

                callback.log("VM already exists and belongs to this controller. Reusing existing VM.");
                LOGGER.log(Level.FINE, "VirtualMachine {0} ownership verified, reusing", name);
                return existing.getMetadata().getUid();
            }
            throw e;
        }
    }

    /**
     * Gets the current status of a VirtualMachine as a human-readable string.
     * 
     * @param name The VM name
     * @return Status string describing the current VM state
     */
    @SuppressWarnings("unchecked")
    public String getVMStatus(String name) {
        try {
            // First check VM status
            GenericKubernetesResource vm = client.genericKubernetesResources(VM_CONTEXT)
                    .inNamespace(namespace)
                    .withName(name)
                    .get();

            if (vm == null) {
                return "VM not found";
            }

            Map<String, Object> vmStatus = (Map<String, Object>) vm.getAdditionalProperties().get("status");
            StringBuilder status = new StringBuilder();
            
            if (vmStatus != null) {
                // Check printableStatus which gives a nice summary
                Object printableStatus = vmStatus.get("printableStatus");
                if (printableStatus != null) {
                    status.append(printableStatus.toString());
                }
                
                // Check conditions for more detail
                List<Map<String, Object>> conditions = (List<Map<String, Object>>) vmStatus.get("conditions");
                if (conditions != null) {
                    for (Map<String, Object> condition : conditions) {
                        String type = (String) condition.get("type");
                        String condStatus = (String) condition.get("status");
                        String reason = (String) condition.get("reason");
                        
                        if ("Ready".equals(type) && "True".equals(condStatus)) {
                            if (status.length() > 0) status.append(" - ");
                            status.append("Ready");
                        } else if (reason != null && !"".equals(reason)) {
                            if (status.length() > 0) status.append(" (");
                            status.append(reason);
                            if (status.toString().contains("(")) status.append(")");
                        }
                    }
                }
            }
            
            // Also check VMI status
            GenericKubernetesResource vmi = client.genericKubernetesResources(VMI_CONTEXT)
                    .inNamespace(namespace)
                    .withName(name)
                    .get();

            if (vmi != null) {
                Map<String, Object> vmiStatus = (Map<String, Object>) vmi.getAdditionalProperties().get("status");
                if (vmiStatus != null) {
                    String phase = (String) vmiStatus.get("phase");
                    if (phase != null) {
                        if (status.length() > 0) status.append(", VMI: ");
                        status.append(phase);
                    }
                }
            } else if (status.length() == 0) {
                status.append("VMI not yet created");
            }

            return status.length() > 0 ? status.toString() : "Unknown";
            
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Error getting VM status for " + name, e);
            return "Error: " + e.getMessage();
        }
    }

    /**
     * Gets the status of a DataVolume in the default (VM) namespace.
     * 
     * @param dvName The DataVolume name (typically vmName + "-disk")
     * @return A status object with phase, progress, and any error message
     */
    public DataVolumeStatus getDataVolumeStatus(String dvName) {
        return getDataVolumeStatus(dvName, namespace);
    }

    /**
     * Gets the status of a DataVolume in a specific namespace.
     * 
     * @param dvName The DataVolume name
     * @param dvNamespace The namespace where the DataVolume resides
     * @return A status object with phase, progress, and any error message
     */
    @SuppressWarnings("unchecked")
    public DataVolumeStatus getDataVolumeStatus(String dvName, String dvNamespace) {
        try {
            GenericKubernetesResource dv = client.genericKubernetesResources(DV_CONTEXT)
                    .inNamespace(dvNamespace)
                    .withName(dvName)
                    .get();

            if (dv == null) {
                return new DataVolumeStatus("NotFound", null, "DataVolume not found - may still be creating");
            }

            Map<String, Object> status = (Map<String, Object>) dv.getAdditionalProperties().get("status");
            if (status == null) {
                return new DataVolumeStatus("Pending", null, "DataVolume has no status yet");
            }

            String phase = (String) status.get("phase");
            String progress = (String) status.get("progress");
            
            // Check conditions for any errors
            String errorMessage = null;
            List<Map<String, Object>> conditions = (List<Map<String, Object>>) status.get("conditions");
            if (conditions != null) {
                for (Map<String, Object> condition : conditions) {
                    String type = (String) condition.get("type");
                    String condStatus = (String) condition.get("status");
                    String reason = (String) condition.get("reason");
                    String message = (String) condition.get("message");
                    
                    // Check for error conditions
                    if ("False".equals(condStatus) && message != null && !message.isEmpty()) {
                        if ("Ready".equals(type) || "Running".equals(type)) {
                            errorMessage = reason + ": " + message;
                        }
                    }
                }
            }

            return new DataVolumeStatus(phase, progress, errorMessage);
            
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Error getting DataVolume status for " + dvName, e);
            return new DataVolumeStatus("Error", null, e.getMessage());
        }
    }

    /**
     * Status information for a DataVolume.
     */
    public static class DataVolumeStatus {
        private final String phase;
        private final String progress;
        private final String message;

        public DataVolumeStatus(String phase, String progress, String message) {
            this.phase = phase;
            this.progress = progress;
            this.message = message;
        }

        public String getPhase() {
            return phase;
        }

        public String getProgress() {
            return progress;
        }

        public String getMessage() {
            return message;
        }

        public boolean isComplete() {
            return "Succeeded".equals(phase);
        }

        public boolean isFailed() {
            return "Failed".equals(phase);
        }

        public boolean isInProgress() {
            return "ImportInProgress".equals(phase) || "CloneInProgress".equals(phase) 
                || "CloneScheduled".equals(phase) || "ImportScheduled".equals(phase)
                || "Pending".equals(phase) || "PVCBound".equals(phase);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("Phase: ").append(phase != null ? phase : "unknown");
            if (progress != null && !progress.isEmpty()) {
                sb.append(", Progress: ").append(progress);
            }
            if (message != null && !message.isEmpty()) {
                sb.append(" (").append(message).append(")");
            }
            return sb.toString();
        }
    }

    /**
     * Deletes a KubeVirt VirtualMachine and its associated cloud-init Secret.
     * Validates ownership before deletion to prevent accidental removal of VMs
     * belonging to other controllers, clouds, or templates.
     *
     * @param name         VM name to delete
     * @param cloudName    Expected cloud name for ownership validation
     * @param templateName Expected template name for ownership validation
     * @throws IllegalStateException if the VM doesn't belong to this instance
     */
    public void deleteVM(String name, String cloudName, String templateName) {
        LOGGER.log(Level.FINE, "Deleting VirtualMachine: {0} in namespace: {1}", new Object[]{name, namespace});

        validateVMOwnership(name, cloudName, templateName);

        // Delete associated cloud-init secret (best-effort, before VM deletion)
        deleteCloudInitSecret(name);

        client.genericKubernetesResources(VM_CONTEXT)
                .inNamespace(namespace)
                .withName(name)
                .delete();

        LOGGER.log(Level.FINE, "VirtualMachine {0} deleted", name);
    }

    /**
     * Counts VMs in the namespace that belong to a specific cloud.
     * This counts VMs based on Kubernetes labels, which includes VMs that are
     * still being provisioned and not yet registered as Jenkins nodes.
     *
     * @param cloudName The cloud name to filter by
     * @return The number of VMs with the matching cloud label
     */
    public int countVMsByCloud(String cloudName) {
        try {
            String sanitizedCloudName = sanitizeLabelValue(cloudName);
            var vms = client.genericKubernetesResources(VM_CONTEXT)
                    .inNamespace(namespace)
                    .withLabel(KubeVirtConfiguration.CLOUD_LABEL_KEY, sanitizedCloudName)
                    .list();
            int count = vms.getItems().size();
            LOGGER.log(Level.FINE, "Counted {0} VMs for cloud ''{1}'' in namespace {2}",
                    new Object[]{count, cloudName, namespace});
            return count;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error counting VMs for cloud " + cloudName + ": " + e.getMessage(), e);
            // Return 0 on error - we'll fall back to counting registered nodes
            return 0;
        }
    }

    /**
     * Counts VMs in the namespace that belong to a specific cloud and template.
     * This counts VMs based on Kubernetes labels, which includes VMs that are
     * still being provisioned and not yet registered as Jenkins nodes.
     *
     * @param cloudName The cloud name to filter by
     * @param templateName The template name to filter by
     * @return The number of VMs with matching cloud and template labels
     */
    public int countVMsByCloudAndTemplate(String cloudName, String templateName) {
        try {
            String sanitizedCloudName = sanitizeLabelValue(cloudName);
            String sanitizedTemplateName = sanitizeLabelValue(templateName);
            var vms = client.genericKubernetesResources(VM_CONTEXT)
                    .inNamespace(namespace)
                    .withLabel(KubeVirtConfiguration.CLOUD_LABEL_KEY, sanitizedCloudName)
                    .withLabel(KubeVirtConfiguration.TEMPLATE_LABEL_KEY, sanitizedTemplateName)
                    .list();
            int count = vms.getItems().size();
            LOGGER.log(Level.FINE, "Counted {0} VMs for cloud ''{1}'', template ''{2}'' in namespace {3}",
                    new Object[]{count, cloudName, templateName, namespace});
            return count;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error counting VMs for cloud " + cloudName +
                    ", template " + templateName + ": " + e.getMessage(), e);
            // Return 0 on error - we'll fall back to counting registered nodes
            return 0;
        }
    }

    /**
     * Gets the creation timestamp of a VM.
     * Used for orphan detection to implement a grace period.
     *
     * @param name The VM name
     * @return The creation timestamp in ISO-8601 format, or null if not found
     */
    public String getVMCreationTimestamp(String name) {
        try {
            var vm = client.genericKubernetesResources(VM_CONTEXT)
                    .inNamespace(namespace)
                    .withName(name)
                    .get();
            if (vm != null && vm.getMetadata() != null) {
                return vm.getMetadata().getCreationTimestamp();
            }
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Error getting creation timestamp for VM " + name, e);
        }
        return null;
    }

    /**
     * Gets the template name from a VM's labels.
     * Used for orphan detection to look up template-specific grace period.
     *
     * @param name The VM name
     * @return The template name from the VM's labels, or null if not found
     */
    public String getVMTemplateName(String name) {
        try {
            var vm = client.genericKubernetesResources(VM_CONTEXT)
                    .inNamespace(namespace)
                    .withName(name)
                    .get();
            if (vm != null && vm.getMetadata() != null && vm.getMetadata().getLabels() != null) {
                return vm.getMetadata().getLabels().get(KubeVirtConfiguration.TEMPLATE_LABEL_KEY);
            }
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Error getting template name for VM " + name, e);
        }
        return null;
    }

    /**
     * Lists VM names in the namespace that belong to a specific cloud and were
     * created by this Jenkins controller. Both the cloud label and the controller
     * label are required so that two Jenkins instances sharing the same KubeVirt
     * cluster with the same cloud name cannot interfere with each other's VMs
     * during orphan cleanup.
     *
     * <p>If the controller label value cannot be determined (e.g. Jenkins root URL
     * is not configured) the query falls back to filtering by cloud label only,
     * which preserves the previous behaviour but logs a warning.</p>
     *
     * @param cloudName The cloud name to filter by
     * @return List of VM names matching both labels, empty list on error
     */
    public List<String> listVMsByCloud(String cloudName) {
        try {
            String sanitizedCloudName = sanitizeLabelValue(cloudName);
            String controllerLabel = getControllerLabelValue();

            var query = client.genericKubernetesResources(VM_CONTEXT)
                    .inNamespace(namespace)
                    .withLabel(KubeVirtConfiguration.CLOUD_LABEL_KEY, sanitizedCloudName);

            if (!controllerLabel.isEmpty()) {
                query = query.withLabel(KubeVirtConfiguration.CONTROLLER_LABEL_KEY, controllerLabel);
            } else {
                LOGGER.log(Level.WARNING,
                        "Jenkins root URL is not configured; orphan detection for cloud ''{0}'' will list VMs " +
                        "from all controllers sharing this cloud name. Configure a root URL to scope cleanup " +
                        "to this controller only.", cloudName);
            }

            var vms = query.list();
            List<String> vmNames = vms.getItems().stream()
                    .map(vm -> vm.getMetadata().getName())
                    .collect(java.util.stream.Collectors.toList());
            LOGGER.log(Level.FINE, "Listed {0} VMs for cloud ''{1}'' (controller: ''{2}'') in namespace {3}: {4}",
                    new Object[]{vmNames.size(), cloudName, controllerLabel.isEmpty() ? "<unscoped>" : controllerLabel,
                            namespace, vmNames});
            return vmNames;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error listing VMs for cloud " + cloudName + ": " + e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * Gets the IP address of a running VirtualMachineInstance.
     */
    @SuppressWarnings("unchecked")
    public String getVMIp(String name) {
        GenericKubernetesResource vmi = client.genericKubernetesResources(VMI_CONTEXT)
                .inNamespace(namespace)
                .withName(name)
                .get();

        if (vmi == null) {
            return null;
        }

        Map<String, Object> status = (Map<String, Object>) vmi.getAdditionalProperties().get("status");
        if (status == null) {
            return null;
        }

        List<Map<String, Object>> interfaces = (List<Map<String, Object>>) status.get("interfaces");
        if (interfaces != null && !interfaces.isEmpty()) {
            return (String) interfaces.get(0).get("ipAddress");
        }

        return null;
    }

    /**
     * Gets detailed diagnostic information about a VMI's network interfaces.
     * Useful for debugging why an IP address might not be assigned.
     * 
     * @param name The VM name
     * @return Detailed interface information or a diagnostic message
     */
    @SuppressWarnings("unchecked")
    public String getNetworkDiagnostics(String name) {
        try {
            GenericKubernetesResource vmi = client.genericKubernetesResources(VMI_CONTEXT)
                    .inNamespace(namespace)
                    .withName(name)
                    .get();

            if (vmi == null) {
                return "VMI not found - VM may still be starting";
            }

            Map<String, Object> status = (Map<String, Object>) vmi.getAdditionalProperties().get("status");
            if (status == null) {
                return "VMI has no status yet";
            }

            StringBuilder diag = new StringBuilder();
            
            // Check phase
            String phase = (String) status.get("phase");
            diag.append("VMI Phase: ").append(phase != null ? phase : "unknown").append("\n");

            // Check interfaces
            List<Map<String, Object>> interfaces = (List<Map<String, Object>>) status.get("interfaces");
            if (interfaces == null || interfaces.isEmpty()) {
                diag.append("  No network interfaces reported by guest agent\n");
                diag.append("  Possible causes:\n");
                diag.append("    - QEMU guest agent not installed or not running in the VM\n");
                diag.append("    - VM is still booting\n");
                diag.append("    - Network configuration not complete\n");
            } else {
                diag.append("Interfaces (").append(interfaces.size()).append("):\n");
                for (int i = 0; i < interfaces.size(); i++) {
                    Map<String, Object> iface = interfaces.get(i);
                    diag.append("  [").append(i).append("] ");
                    diag.append("name=").append(iface.get("name"));
                    diag.append(", mac=").append(iface.get("mac"));
                    String ip = (String) iface.get("ipAddress");
                    diag.append(", ip=").append(ip != null ? ip : "NOT ASSIGNED");
                    
                    // Check for additional IPs (ipAddresses array)
                    List<String> ipAddresses = (List<String>) iface.get("ipAddresses");
                    if (ipAddresses != null && !ipAddresses.isEmpty()) {
                        diag.append(", ipAddresses=").append(ipAddresses);
                    }
                    diag.append("\n");
                }
            }

            // Check guest agent info
            Map<String, Object> guestOSInfo = (Map<String, Object>) status.get("guestOSInfo");
            if (guestOSInfo != null) {
                diag.append("Guest OS: ").append(guestOSInfo.get("name")).append(" ");
                diag.append(guestOSInfo.get("version")).append("\n");
            } else {
                diag.append("Guest OS: No info (guest agent may not be reporting)\n");
            }

            // Check conditions for any issues
            List<Map<String, Object>> conditions = (List<Map<String, Object>>) status.get("conditions");
            if (conditions != null) {
                for (Map<String, Object> condition : conditions) {
                    String type = (String) condition.get("type");
                    String condStatus = (String) condition.get("status");
                    String reason = (String) condition.get("reason");
                    String message = (String) condition.get("message");
                    
                    // Report any non-ready conditions
                    if (!"True".equals(condStatus) && reason != null) {
                        diag.append("Condition ").append(type).append(": ");
                        diag.append(condStatus).append(" (").append(reason).append(")");
                        if (message != null && !message.isEmpty()) {
                            diag.append(" - ").append(message);
                        }
                        diag.append("\n");
                    }
                }
            }

            return diag.toString();
            
        } catch (Exception e) {
            return "Error getting diagnostics: " + e.getMessage();
        }
    }

    /**
     * Gets a comprehensive status dump of a VM, useful for debugging provisioning failures.
     * 
     * @param name The VM name
     * @return Detailed status information
     */
    @SuppressWarnings("unchecked")
    public String getFullVMDiagnostics(String name) {
        StringBuilder diag = new StringBuilder();
        diag.append("=== VM Diagnostics for: ").append(name).append(" ===\n\n");

        try {
            // VM status
            GenericKubernetesResource vm = client.genericKubernetesResources(VM_CONTEXT)
                    .inNamespace(namespace)
                    .withName(name)
                    .get();

            if (vm == null) {
                diag.append("VirtualMachine not found in namespace ").append(namespace).append("\n");
                return diag.toString();
            }

            Map<String, Object> vmStatus = (Map<String, Object>) vm.getAdditionalProperties().get("status");
            if (vmStatus != null) {
                diag.append("VM Status:\n");
                diag.append("  printableStatus: ").append(vmStatus.get("printableStatus")).append("\n");
                diag.append("  ready: ").append(vmStatus.get("ready")).append("\n");
                diag.append("  created: ").append(vmStatus.get("created")).append("\n");
                
                // Check for any volume status (useful for DataSource cloning)
                List<Map<String, Object>> volumeStatuses = (List<Map<String, Object>>) vmStatus.get("volumeSnapshotStatuses");
                if (volumeStatuses != null) {
                    diag.append("  Volume statuses:\n");
                    for (Map<String, Object> volStatus : volumeStatuses) {
                        diag.append("    - ").append(volStatus).append("\n");
                    }
                }
                
                // Conditions
                List<Map<String, Object>> conditions = (List<Map<String, Object>>) vmStatus.get("conditions");
                if (conditions != null && !conditions.isEmpty()) {
                    diag.append("  Conditions:\n");
                    for (Map<String, Object> cond : conditions) {
                        diag.append("    - ").append(cond.get("type")).append(": ");
                        diag.append(cond.get("status"));
                        if (cond.get("reason") != null) {
                            diag.append(" (").append(cond.get("reason")).append(")");
                        }
                        if (cond.get("message") != null && !cond.get("message").toString().isEmpty()) {
                            diag.append("\n      Message: ").append(cond.get("message"));
                        }
                        diag.append("\n");
                    }
                }
            }

            diag.append("\n");

            // VMI status
            diag.append("VMI (Instance) Status:\n");
            diag.append(getNetworkDiagnostics(name));

        } catch (Exception e) {
            diag.append("Error gathering diagnostics: ").append(e.getMessage()).append("\n");
            LOGGER.log(Level.WARNING, "Error getting full VM diagnostics for " + name, e);
        }

        diag.append("\n=== Troubleshooting Tips ===\n");
        diag.append("1. Ensure the VM image has qemu-guest-agent installed and enabled\n");
        diag.append("2. Check if the VM can obtain an IP via DHCP or has static IP configured\n");
        diag.append("3. Verify network policies allow the VM to communicate\n");
        diag.append("4. Check if the pod network (CNI) is functioning correctly\n");
        diag.append("5. Review VM events: kubectl get events -n ").append(namespace)
            .append(" --field-selector involvedObject.name=").append(name).append("\n");
        diag.append("6. Check VMI logs: virtctl console ").append(name).append(" -n ").append(namespace).append("\n");

        return diag.toString();
    }

    /**
     * Checks if a VirtualMachineInstance is in Running state.
     */
    @SuppressWarnings("unchecked")
    public boolean isVMReady(String name) {
        GenericKubernetesResource vmi = client.genericKubernetesResources(VMI_CONTEXT)
                .inNamespace(namespace)
                .withName(name)
                .get();

        if (vmi == null) {
            return false;
        }

        Map<String, Object> status = (Map<String, Object>) vmi.getAdditionalProperties().get("status");
        if (status == null) {
            return false;
        }

        String phase = (String) status.get("phase");
        return "Running".equals(phase);
    }

    /**
     * Tests the connection to the Kubernetes cluster and verifies KubeVirt is available.
     * Returns a detailed message about the connection status.
     *
     * @return A message describing the connection test result
     * @throws Exception if the connection test fails
     */
    public String testConnection() throws Exception {
        StringBuilder result = new StringBuilder();

        // Step 1: Test basic cluster connectivity
        LOGGER.log(Level.FINE, "Testing connection to Kubernetes cluster at: {0}", client.getMasterUrl());
        try {
            String serverVersion = client.getKubernetesVersion().getGitVersion();
            result.append("Cluster version: ").append(serverVersion).append("\n");
            LOGGER.log(Level.FINE, "Successfully connected to cluster. Version: {0}", serverVersion);
        } catch (Exception e) {
            String msg = "Failed to connect to Kubernetes cluster: " + e.getMessage();
            LOGGER.log(Level.SEVERE, msg, e);
            throw new Exception(msg, e);
        }

        // Step 2: Test namespace access
        LOGGER.log(Level.FINE, "Testing access to namespace: {0}", namespace);
        try {
            var ns = client.namespaces().withName(namespace).get();
            if (ns == null) {
                String msg = "Namespace '" + namespace + "' does not exist or is not accessible";
                LOGGER.log(Level.SEVERE, msg);
                throw new Exception(msg);
            }
            result.append("Namespace '").append(namespace).append("' is accessible\n");
            LOGGER.log(Level.FINE, "Namespace {0} is accessible", namespace);
        } catch (io.fabric8.kubernetes.client.KubernetesClientException e) {
            String msg = "Failed to access namespace '" + namespace + "': " + e.getMessage();
            LOGGER.log(Level.SEVERE, msg, e);
            throw new Exception(msg, e);
        }

        // Step 3: Test KubeVirt API access (list VMs in namespace)
        LOGGER.log(Level.FINE, "Testing KubeVirt API access in namespace: {0}", namespace);
        try {
            var vms = client.genericKubernetesResources(VM_CONTEXT)
                    .inNamespace(namespace)
                    .list();
            int vmCount = vms.getItems().size();
            result.append("KubeVirt API accessible. Found ").append(vmCount).append(" VirtualMachine(s) in namespace\n");
            LOGGER.log(Level.FINE, "KubeVirt API accessible. Found {0} VMs in namespace {1}", new Object[]{vmCount, namespace});
        } catch (io.fabric8.kubernetes.client.KubernetesClientException e) {
            String msg = "Failed to access KubeVirt API. Ensure KubeVirt or OpenShift Virtualization is installed and you have permissions to list VirtualMachines: " + e.getMessage();
            LOGGER.log(Level.SEVERE, msg, e);
            throw new Exception(msg, e);
        }

        return result.toString();
    }

    /**
     * Closes the Kubernetes client.
     */
    @Override
    public void close() {
        if (client != null) {
            client.close();
        }
    }
}
