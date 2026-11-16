FROM debian:bookworm-slim

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates \
    curl \
    gnupg \
    iproute2 \
    iptables \
    kmod \
    nftables \
    openssl \
    procps \
    python3 \
    wireguard-tools \
  && mkdir -p /etc/apt/keyrings \
  && curl -fsSL https://sing-box.app/gpg.key -o /etc/apt/keyrings/sagernet.asc \
  && chmod a+r /etc/apt/keyrings/sagernet.asc \
  && printf 'Types: deb\nURIs: https://deb.sagernet.org/\nSuites: *\nComponents: *\nEnabled: yes\nSigned-By: /etc/apt/keyrings/sagernet.asc\n' > /etc/apt/sources.list.d/sagernet.sources \
  && apt-get update \
  && apt-get install -y --no-install-recommends sing-box \
  && rm -rf /var/lib/apt/lists/*

WORKDIR /opt/tunnel-relay

COPY config.json /opt/tunnel-relay/config.template.json
COPY sing-box.service /opt/tunnel-relay/sing-box.service
COPY setup-singbox.sh /opt/tunnel-relay/setup-singbox.sh
COPY entrypoint.sh /opt/tunnel-relay/entrypoint.sh
COPY peer-admin.py /opt/tunnel-relay/peer-admin.py

RUN chmod +x /opt/tunnel-relay/entrypoint.sh /opt/tunnel-relay/setup-singbox.sh /opt/tunnel-relay/peer-admin.py \
  && mkdir -p /etc/wireguard /etc/sing-box/tls

# Expose production Anti-DPI & VPN transports:
# - 443/tcp   : sing-box VLESS + Reality + XTLS-Vision (TLS 1.3 SNI masquerading)
# - 8443/tcp  : sing-box Trojan over TLS (H2/HTTP1.1)
# - 51820/udp : WireGuard VPN (wg0)
EXPOSE 443/tcp 8443/tcp 51820/udp

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD sing-box check -c /etc/sing-box/config.json && pgrep -x sing-box >/dev/null || exit 1

ENTRYPOINT ["/opt/tunnel-relay/entrypoint.sh"]
