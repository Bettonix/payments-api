#!/usr/bin/env bash
set -euo pipefail

# setup-docker.sh
# Instalação automatizada do Docker Engine e Compose Plugin no Debian (WSL2).
# Uso: wsl -d Debian -u root -- bash infra/wsl/setup-docker.sh [--expose-tcp]

EXPOSE_TCP=false
for arg in "$@"; do
  if [ "$arg" == "--expose-tcp" ]; then
    EXPOSE_TCP=true
  fi
done

echo "==> [1/5] Atualizando repositórios e instalando dependências base..."
apt-get update -y
apt-get install -y --no-install-recommends ca-certificates curl gnupg lsb-release

echo "==> [2/5] Configurando chave GPG e repositório oficial da Docker..."
install -m 0755 -d /etc/apt/keyrings
if [ ! -f /etc/apt/keyrings/docker.asc ]; then
  curl -fsSL https://download.docker.com/linux/debian/gpg -o /etc/apt/keyrings/docker.asc
  chmod a+r /etc/apt/keyrings/docker.asc
fi

DEBIAN_CODENAME="$(. /etc/os-release && echo "$VERSION_CODENAME")"
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/debian ${DEBIAN_CODENAME} stable" \
  | tee /etc/apt/sources.list.d/docker.list > /dev/null

echo "==> [3/5] Instalando Docker CE, CLI e Compose Plugin..."
apt-get update -y
apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

echo "==> [4/5] Configurando permissões do usuário..."
TARGET_USER="${SUDO_USER:-}"
if [ -z "$TARGET_USER" ]; then
  TARGET_USER="$(awk -F: '$3 >= 1000 && $3 < 60000 {print $1; exit}' /etc/passwd || true)"
fi

if [ -n "$TARGET_USER" ]; then
  echo "Adicionando usuário '$TARGET_USER' ao grupo docker..."
  groupadd -f docker
  usermod -aG docker "$TARGET_USER"
fi

if [ "$EXPOSE_TCP" = true ]; then
  echo "==> Configurando daemon Docker para escutar em tcp://127.0.0.1:2375..."
  mkdir -p /etc/systemd/system/docker.service.d
  cat <<'EOF' > /etc/systemd/system/docker.service.d/override.conf
[Service]
ExecStart=
ExecStart=/usr/bin/dockerd -H fd:// -H tcp://127.0.0.1:2375 --containerd=/run/containerd/containerd.sock
EOF
  systemctl daemon-reload
fi

echo "==> [5/5] Habilitando e iniciando o serviço Docker..."
if command -v systemctl >/dev/null 2>&1 && systemctl is-system-running >/dev/null 2>&1; then
  systemctl enable --now docker
  systemctl restart docker
else
  service docker start || true
fi

echo "==> Instalação concluída com sucesso!"
docker --version
docker compose version
