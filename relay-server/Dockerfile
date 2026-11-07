FROM debian:bookworm-slim

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates \
    curl \
    dnsmasq \
    iproute2 \
    iptables \
    kmod \
    nftables \
    nodejs \
    openssh-server \
    openssl \
    procps \
    socat \
    wireguard-tools \
  && rm -rf /var/lib/apt/lists/*

WORKDIR /opt/tunnel-relay

COPY gateway.mjs /opt/tunnel-relay/gateway.mjs
COPY entrypoint.sh /opt/tunnel-relay/entrypoint.sh

RUN chmod +x /opt/tunnel-relay/entrypoint.sh \
  && mkdir -p /etc/wireguard /etc/tunnel-relay/tls /var/run/sshd

# Expose all Tunnel Console transports:
# - 51820/udp : WireGuard VPN
# - 443/tcp   : TLS + SNI + HTTP Custom Payload / WebSocket Upgrade + /health
# - 8443/tcp  : Alternate TLS + SNI + WebSocket + /health
# - 80/tcp    : HTTP Custom Payload + BugHostProbeEngine corridor port
# - 8080/tcp  : Alternate HTTP Custom Payload + corridor port
# - 1080/tcp  : SOCKS5 Bridge Proxy
# - 2222/tcp  : SSH Tunnel Transport
EXPOSE 51820/udp 443/tcp 8443/tcp 80/tcp 8080/tcp 1080/tcp 2222/tcp

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD curl -kfsS https://127.0.0.1:443/health || exit 1

ENTRYPOINT ["/opt/tunnel-relay/entrypoint.sh"]
