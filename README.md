# KubeVirt Cloud Plugin for Jenkins

[![Jenkins Plugin](https://img.shields.io/badge/Jenkins-Plugin-blue.svg)](https://plugins.jenkins.io/)
[![License](https://img.shields.io/github/license/jenkinsci/kubevirt-cloud-plugin)](LICENSE)

Jenkins plugin to run dynamic agents as Virtual Machines in Kubernetes clusters running [KubeVirt](https://kubevirt.io/).

The plugin creates a KubeVirt VirtualMachine for each agent started, and deletes it after the build completes or after an idle timeout.

Agents connect to the Jenkins controller via SSH after the VM boots and cloud-init configures the SSH keys.

# 📜 Table of Contents

- [Features](#features)
- [Prerequisites](#prerequisites)
- [Configuration](#configuration)
  - [Cloud Configuration](#cloud-configuration)
  - [Template Configuration](#template-configuration)
  - [Cloud-Init Configuration](#cloud-init-configuration)
- [Provisioning Behavior](#provisioning-behavior)
- [Usage](#usage)
  - [Using Labels](#using-labels)
  - [Pipeline Example](#pipeline-example)
- [Template Options Reference](#template-options-reference)
  - [Basic Settings](#basic-settings)
  - [Resources](#resources)
  - [Cloud-Init Configuration](#cloud-init-configuration-1)
  - [Disk Configuration](#disk-configuration)
  - [SSH Configuration](#ssh-configuration)
  - [Agent Retention](#agent-retention)
  - [VM Provisioning](#vm-provisioning)
- [Connection Types](#connection-types)
- [Troubleshooting](#troubleshooting)
- [Development](#development)

# Features

* **Dynamic VM Provisioning** - Automatically create VMs when builds request agents with matching labels
* **Multiple Disk Sources** - Support for both ContainerDisk images and DataSource cloning
* **Flexible SSH Connectivity** - Connect via direct SSH or virtctl port-forward tunneling
* **Cloud-Init Integration** - Full support for VM initialization via cloud-init, including SSH key injection
* **Automatic Cleanup** - VMs are deleted after builds complete or after configurable idle timeout
* **Single-Use Agents** - Option to dispose VMs immediately after a single build
* **Resource Configuration** - Configure CPU, memory, and disk size per template
* **Cloud Stats Integration** - Track provisioning activities via the cloud-stats plugin

# Prerequisites

1. **Kubernetes Cluster with KubeVirt**
   - A Kubernetes cluster (1.20+) with [KubeVirt](https://kubevirt.io/), and [KubeVirt CDI](https://kubevirt.io/user-guide/storage/containerized_data_importer/#containerized-data-importer) installed **OR**
   - An OpenShift cluster option is supported too

2. **Service Account**
   - A service account with permissions to manage VirtualMachines, VirtualMachineInstances, DataVolumes, DataSources, Secrets, and Pods
   - Example permissions needed:
     ```yaml
     apiVersion: rbac.authorization.k8s.io/v1
     kind: ClusterRole
     metadata:
       name: jenkins-kubevirt-ns-reader
     rules:
       - apiGroups: [""]
         resources: ["namespaces"]
         verbs: ["get"]
     ---
     apiVersion: rbac.authorization.k8s.io/v1
     kind: Role
     metadata:
       name: jenkins-kubevirt
       namespace: jenkins-agents
     rules:
      - apiGroups: ["kubevirt.io"]
        resources: ["virtualmachines", "virtualmachineinstances"]
        verbs: ["get", "list", "watch", "create", "delete"]
      - apiGroups: ["subresources.kubevirt.io"]
        resources: ["virtualmachineinstances/portforward"]
        verbs: ["get"]
      - apiGroups: ["cdi.kubevirt.io"]
        resources: ["datavolumes", "datasources"]
        verbs: ["get", "list", "watch", "create", "delete"]
      - apiGroups: [""]
        resources: ["secrets"]
        verbs: ["get", "create", "update", "patch", "delete"]
     ```
     > **Note:** The `namespaces` permission requires a **ClusterRole** (bound via a ClusterRoleBinding) because namespaces are cluster-scoped resources. This is used by the plugin's "Test Connection" feature to verify access to the configured namespace.
     >
     > Alternatively, you can bind the built-in `view` ClusterRole instead of creating a custom one:
     > ```bash
     > kubectl create clusterrolebinding jenkins-kubevirt-ns-reader \
     >   --clusterrole=view \
     >   --serviceaccount=jenkins-agents:jenkins-kubevirt
     > ```
     > The `view` ClusterRole already includes `get` access to namespaces (along with read access to most other resources), and is available by default in every Kubernetes cluster.

3. **VM Images**
   - VM images must have:
     - **cloud-init** installed and enabled (for SSH key injection)
     - **qemu-guest-agent** installed and enabled (for IP address reporting)
     - **SSH server** installed and enabled
     - **Java** installed (for running the Jenkins agent)

4. **Jenkins Plugins**
   - [SSH Slaves plugin](https://plugins.jenkins.io/ssh-slaves/) (ssh-slaves)
   - [Config File Provider plugin](https://plugins.jenkins.io/config-file-provider/) (config-file-provider)
   - [Cloud Stats plugin](https://plugins.jenkins.io/cloud-stats/) (cloud-stats) - optional

# Configuration

## Cloud Configuration

1. Navigate to **Manage Jenkins** → **Nodes and Clouds** → **Clouds**
2. Click **Add a new cloud** → **KubeVirt / OpenShift Virtualization**
3. Configure the following:

| Field | Description |
|-------|-------------|
| **Name** | A unique name for this cloud configuration |
| **Kubernetes Server URL** | The API server URL (e.g., `https://api.cluster.example.com:6443`) |
| **Credentials** | Service account token as Username/Password credentials (username is ignored, password is the token) |
| **Namespace** | The Kubernetes namespace where VMs will be created |
| **Ignore SSL Errors** | Enable if using self-signed certificates |
| **VM Count Cap** | Maximum concurrent VMs across all templates. `0` = unlimited. Default: `10` |
| **Enable Orphan Cleanup** | Periodically detect and delete VMs that have no registered Jenkins agent. Default: enabled |

Click **Test Connection** to verify connectivity to the cluster and KubeVirt API.

## Template Configuration

Add one or more VM templates under the cloud configuration. Each template defines a type of agent VM:

1. Click **Add KubeVirt Template**
2. Configure the template settings (see [Template Options Reference](#template-options-reference))
3. Use labels to match specific job types to appropriate templates

## Cloud-Init Configuration

Cloud-init files are managed separately via **Manage Jenkins** → **Managed Files**:

1. Click **Add a new Config** → **KubeVirt Cloud-Init**
2. Create a cloud-init YAML file with SSH key injection:

```yaml
#cloud-config
users:
  - name: jenkins
    sudo: ALL=(ALL) NOPASSWD:ALL
    shell: /bin/bash
    ssh_authorized_keys:
      - ssh-rsa AAAA... your-public-key-here

# Ensure SSH is running
runcmd:
  - systemctl enable sshd
  - systemctl start sshd

# Install Java if not present
packages:
  - java-17-openjdk-headless
```

> **Note:** The SSH public key in cloud-init must match the private key configured in the SSH credentials for the template.

## Provisioning Behavior

This plugin uses a custom **No-Delay Provisioner Strategy** so that VM provisioning starts as soon as a buildable item enters the queue, instead of waiting for Jenkins' default periodic `NodeProvisioner` cycle. A **Fast Provisioning** queue listener wakes the provisioner immediately when a `KubeVirtCloud` can serve the requested label.

### Cloud order (multiple KubeVirt clouds)

If you configure more than one **KubeVirt / OpenShift Virtualization** cloud under **Manage Jenkins → Clouds**, the strategy distributes work using **round-robin** over the KubeVirt clouds **in the order they appear in that list** (top to bottom). Each new provisioning attempt advances the starting cloud. Non-KubeVirt clouds (EC2, Kubernetes, etc.) are ignored for this rotation; the plugin only round-robins among `KubeVirtCloud` entries.

Round-robin is a **load-balancing tendency**, not a strict guarantee that VMs are created one-per-cloud in lockstep. Many real-world conditions change which cloud actually provisions:

| Situation | Effect |
|-----------|--------|
| **VM Count Cap** or **Instance Cap** reached | That cloud or template is skipped; the next cloud in the rotation may provision instead. |
| **Cap set to `0`** | Means **unlimited** concurrent VMs for that scope—not “disable provisioning”. |
| **Label does not match** any template in a cloud | `canProvision()` is false for that cloud; it is skipped. |
| **Cluster unreachable** or API errors | Provisioning from that cloud fails or returns nothing; other clouds may be tried in the same attempt. |
| **Several queued builds at once** | One strategy pass may plan multiple agents from the same cloud until demand is met. |
| **Agents still connecting** | Connecting executors count toward capacity so the strategy does not over-provision during SSH setup. |
| **Partial satisfaction** | If KubeVirt clouds cannot satisfy all demand, remaining strategies may provision elsewhere. |
| **Only one KubeVirt cloud** | Rotation has no effect; template order within the cloud applies (see below). |

To always try clouds in fixed Jenkins list order (no rotation), set at JVM startup:

```text
-DNoDelayProvisionerStrategy.disableCloudRotation=true
```

Or in the **Script Console** (no restart): `NoDelayProvisionerStrategy.DISABLE_CLOUD_ROTATION = true`

The older property `NoDelayProvisionerStrategy.disableCloudShuffle` is still accepted and has the same effect.

### Template order (within one cloud)

Inside each cloud, `provision()` walks **VM templates in list order** (use the up/down controls in the cloud configuration UI). The first template that matches the label and has capacity is used; if it cannot satisfy all demand, later templates in the list may be used in the same call according to the same rules.

### Disabling no-delay provisioning

To fall back to Jenkins' default provisioner behavior for all clouds (not only KubeVirt):

```text
-Dio.jenkins.plugins.kubevirt.disableNoDelayProvisioning=true
```

Or: `NoDelayProvisionerStrategy.DISABLE_NO_DELAY = true` in the Script Console.

# Usage

## Using Labels

Jobs request specific agent types by specifying labels. When a job needs an agent with a specific label, the plugin provisions a VM from the matching template.

### Freestyle Jobs

In a Freestyle job configuration:
1. Check **Restrict where this project can be run**
2. Enter the label expression (e.g., `linux-vm` or `rhel9-large`)

### Pipeline Jobs

Use the `node` step with the appropriate label:

```groovy
node('linux-vm') {
    // Your build steps run inside the KubeVirt VM
    sh 'echo "Running on $(hostname)"'
    sh 'java -version'
}
```

## Pipeline Example

```groovy
pipeline {
    agent {
        label 'rhel9-agent'  // Matches a template with this label
    }
    
    stages {
        stage('Build') {
            steps {
                sh 'make build'
            }
        }
        
        stage('Test') {
            steps {
                sh 'make test'
            }
        }
    }
    
    post {
        always {
            // VM will be cleaned up automatically based on idle timeout
            echo 'Build completed'
        }
    }
}
```

# Template Options Reference

## Basic Settings

| Option | Description | Default |
|--------|-------------|---------|
| **Name** | Template name, used as prefix for VM names | Required |
| **Labels** | Space-separated labels for job matching | Required |

## Resources

| Option | Description | Default |
|--------|-------------|---------|
| **CPU** | Reserved CPU allocation (cores or millicores, e.g., `2` or `500m`) | Required |
| **Memory** | Reserved memory allocation (e.g., `4Gi`, `2048Mi`) | Required |
| **CPU Limit** | Maximum CPU the VM can use (optional) | No limit |
| **Memory Limit** | Maximum memory the VM can use (optional) | No limit |

> **Note:** Reserved values are guaranteed to the VM, while Limits cap the maximum. Reserved must be ≤ Limits.

## Cloud-Init Configuration

| Option | Description | Default |
|--------|-------------|---------|
| **Cloud-Init Config** | Reference to a managed cloud-init file | Optional |
| **Add /tmp mount** | Attach a 10Gi ephemeral disk mounted at `/tmp` for faster build operations | Enabled |

## Disk Configuration

| Option | Description | Default |
|--------|-------------|---------|
| **Disk Source Type** | How the boot disk is provided | ContainerDisk |
| **Container Disk Image** | Registry URL for ContainerDisk or Import (e.g., `quay.io/containerdisks/fedora:40`) | Required for ContainerDisk / Import |
| **DataSource Namespace** | Namespace containing the source DataSource (Clone) or where it will be created (Import) | Required for DataSource |
| **DataSource Name** | Name of the DataSource to clone (Clone) or to create (Import) | Required for DataSource |
| **Disk Size** | Size of the root disk (e.g., `30Gi`) | `30Gi` |
| **Access Mode** | PVC access mode for the disk | ReadWriteMany (RWX) |

### Disk Source Types

- **ContainerDisk**: Uses a container image containing the disk. Pulled fresh each time. Good for ephemeral, reproducible builds.
- **DataSource (Clone)**: Clones from an existing golden image DataSource. Creates a persistent DataVolume. Better for large disks.
- **DataSource (Import)**: Imports a container image into a DataSource on first use, then clones from it for subsequent VMs. Combines the convenience of ContainerDisk (just provide an image URL) with the speed of DataSource cloning. The first VM takes longer to provision (image import), but all following VMs are fast (local clone). Requires specifying the DataSource namespace, name, and the image URL to import.

  > **⚠️ Note:** This option creates persistent resources in your cluster that are **not automatically deleted** when the Jenkins template is removed:
  > - A **DataVolume** (`<name>-import`) and its backing **PVC** in the configured DataSource namespace, which store the imported disk image and consume cluster storage.
  > - A **DataSource** (`<name>`) that references the PVC.
  >
  > To reclaim storage when these resources are no longer needed, delete them manually:
  > ```bash
  > kubectl delete datasource <name> -n <namespace>
  > kubectl delete datavolume <name>-import -n <namespace>
  > kubectl delete pvc <name>-import -n <namespace>
  > ```

### Access Modes

- **ReadWriteMany (RWX)**: Allows live migration of VMs between nodes. Recommended.
- **ReadWriteOnce (RWO)**: Disk is bound to a single node. Use when RWX storage is not available.

## SSH Configuration

| Option | Description | Default |
|--------|-------------|---------|
| **Connection Type** | How Jenkins connects to the VM | Direct SSH |
| **SSH Credentials** | Credentials for SSH authentication | Required |
| **SSH Port** | SSH port on the VM | `22` |
| **Remote FS** | Agent working directory on the VM | `/home/jenkins` |
| **Java Path** | Path to Java executable (empty = use PATH) | Empty |

## Agent Retention

| Option | Description | Default |
|--------|-------------|---------|
| **Instance Cap** | Maximum concurrent VMs for this template. `0` = unlimited. | `10` |
| **Idle Minutes** | Time to keep agent running after builds complete. `0` = dispose after first build. | `10` |
| **Orphan Grace Period** | Minutes a VM must exist without a registered agent before being considered orphaned and cleaned up. | `30` |

## VM Provisioning

| Option | Description | Default |
|--------|-------------|---------|
| **Provisioning Timeout** | Maximum minutes to wait for the VM to be ready and get an IP address. Increase if disk cloning takes longer. | `15` |

# Connection Types

The plugin supports two methods for connecting to VMs:

## Direct SSH

Connects directly to the VM's IP address via SSH.

**Requirements:**
- VM must have a routable IP address accessible from the Jenkins controller
- May require network configuration (NodePort, LoadBalancer, or SDN routing)
- Requires qemu-guest-agent to report the IP address

**Best for:** Environments where VMs have routable IPs (e.g., OpenShift with SDN)

## virtctl Port-Forward (SSH)

Uses the Kubernetes API to create a port-forward tunnel to the VM's virt-launcher pod.

**Requirements:**
- Service account must have `pods/portforward` permission
- No network routing needed between Jenkins and VMs

**Best for:** Environments where VMs don't have routable IPs, or when Jenkins is outside the cluster

# Troubleshooting

## VM Provisioning Issues

1. **Check VM status in Kubernetes:**
   ```bash
   kubectl get vm,vmi -n <namespace>
   kubectl describe vm <vm-name> -n <namespace>
   ```

2. **Check DataVolume status (for DataSource cloning):**
   ```bash
   kubectl get dv -n <namespace>
   kubectl describe dv <vm-name>-disk -n <namespace>
   ```

3. **View VM console:**
   ```bash
   virtctl console <vm-name> -n <namespace>
   ```

## SSH Connection Issues

1. **Verify qemu-guest-agent is running:**
   ```bash
   virtctl ssh <user>@<vm-name> -n <namespace>
   systemctl status qemu-guest-agent
   ```

2. **Check if IP is reported:**
   ```bash
   kubectl get vmi <vm-name> -n <namespace> -o jsonpath='{.status.interfaces[0].ipAddress}'
   ```

3. **Verify SSH is accessible:**
   ```bash
   # For direct SSH
   ssh -i <key> <user>@<vm-ip>
   
   # For virtctl port-forward
   virtctl ssh -i <key> <user>@<vm-name> -n <namespace>
   ```

## Common Issues

| Problem | Solution |
|---------|----------|
| VM stuck in "Provisioning" | Check DataVolume status, verify source image exists |
| No IP address reported | Ensure qemu-guest-agent is installed and running |
| SSH connection refused | Verify SSH is running, check firewall rules in VM |
| Authentication failed | Verify SSH key in cloud-init matches credentials private key |
| Java not found | Add Java path in template or install Java via cloud-init |

# Development

## Building the Plugin

```bash
mvn clean install
```

The plugin `.hpi` file will be generated in `target/kubevirt-cloud.hpi`.

## Running a Local Jenkins Instance

```bash
mvn hpi:run
```

This starts Jenkins at `http://localhost:8080/jenkins/` with the plugin installed.

## Running Tests

```bash
mvn test
```

## IDE Setup

Import as a Maven project. The plugin uses:
- Fabric8 Kubernetes Client for Kubernetes/KubeVirt API access
- Jenkins Plugin parent POM for build configuration

## Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Run tests: `mvn verify`
5. Submit a pull request

---

## Related Projects

- [Kubernetes Plugin](https://github.com/jenkinsci/kubernetes-plugin) - Run agents as Kubernetes pods
- [KubeVirt](https://kubevirt.io/) - Run VMs on Kubernetes
- [OpenShift Virtualization](https://docs.openshift.com/container-platform/latest/virt/about_virt/about-virt.html) - KubeVirt distribution for OpenShift
