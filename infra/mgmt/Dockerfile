# The management node, as in the labs: the tools that create the cloud infrastructure (Terraform),
# configure it (Ansible) and talk to the cluster (kubectl), so nothing needs installing on your computer
# but Docker. infra/justfile starts it with the repository mounted at /root/g17.
FROM ubuntu:24.04

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y --no-install-recommends \
        ca-certificates curl gnupg openssh-client python3 software-properties-common just less vim-tiny \
    && rm -rf /var/lib/apt/lists/*

# Ansible
RUN apt-add-repository -y -u ppa:ansible/ansible && apt-get install -y --no-install-recommends ansible \
    && rm -rf /var/lib/apt/lists/*

# Terraform
RUN curl -fsSL https://apt.releases.hashicorp.com/gpg | gpg --dearmor -o /usr/share/keyrings/hashicorp.gpg \
    && echo "deb [signed-by=/usr/share/keyrings/hashicorp.gpg] https://apt.releases.hashicorp.com $(. /etc/os-release && echo $VERSION_CODENAME) main" \
        > /etc/apt/sources.list.d/hashicorp.list \
    && apt-get update && apt-get install -y --no-install-recommends terraform \
    && rm -rf /var/lib/apt/lists/*

# Google Cloud CLI (optional: Terraform authenticates with the service account key file) and kubectl,
# the same minor version as the cluster (kubernetes_version in ansible/group_vars/all.yml).
ARG KUBERNETES_VERSION=1.35
RUN curl -fsSL https://packages.cloud.google.com/apt/doc/apt-key.gpg | gpg --dearmor -o /usr/share/keyrings/cloud.google.gpg \
    && echo "deb [signed-by=/usr/share/keyrings/cloud.google.gpg] https://packages.cloud.google.com/apt cloud-sdk main" \
        > /etc/apt/sources.list.d/google-cloud-sdk.list \
    && curl -fsSL https://pkgs.k8s.io/core:/stable:/v${KUBERNETES_VERSION}/deb/Release.key | gpg --dearmor -o /usr/share/keyrings/kubernetes.gpg \
    && echo "deb [signed-by=/usr/share/keyrings/kubernetes.gpg] https://pkgs.k8s.io/core:/stable:/v${KUBERNETES_VERSION}/deb/ /" \
        > /etc/apt/sources.list.d/kubernetes.list \
    && apt-get update && apt-get install -y --no-install-recommends google-cloud-cli kubectl \
    && rm -rf /var/lib/apt/lists/*

# The cluster's kubeconfig, fetched by ansible/playbooks/02-cluster.yml.
ENV KUBECONFIG=/root/g17/infra/.kube/config
# Ansible ignores an ansible.cfg in a world-writable folder, which a mounted folder can be.
ENV ANSIBLE_CONFIG=/root/g17/infra/ansible/ansible.cfg

WORKDIR /root/g17/infra
CMD ["sleep", "infinity"]
