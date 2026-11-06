#!/bin/bash

# Advanced Tunnel Server Setup Script
# This script sets up a multi-protocol VPN server on Ubuntu 22.04
# For legitimate use only with servers you own

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Check if running as root
if [ "$(id -u)" -ne 0 ]; then
    echo -e "${RED}This script must be run as root${NC}"
    exit 1
fi

# Check OS
if ! grep -qi ubuntu /etc/os-release; then
    echo -e "${RED}This script is for Ubuntu 22.04 only${NC}"
    exit 1
fi

# Get server IP
SERVER_IP=$(curl -s ifconfig.me || echo "unknown")

# Display welcome message
echo -e "${BLUE}"
echo "=========================================="
echo "  Advanced Tunnel Server Setup"
echo "=========================================="
echo -e "${NC}"
echo -e "Detected server IP: ${YELLOW}$SERVER_IP${NC}"
echo ""

# Update system
echo -e "${BLUE}Updating system...${NC}"
apt update -qq
apt upgrade -y -qq
echo -e "${GREEN}✓ System updated${NC}"
echo ""

# Install common dependencies
echo -e "${BLUE}Installing dependencies...${NC}"
apt install -y -qq curl wget git net-tools iproute2 iptables ufw fail2ban unattended-upgrades
apt install -y -qq software-properties-common
echo -e "${GREEN}✓ Dependencies installed${NC}"
echo ""

# Install Node.js for WebSocket server
echo -e "${BLUE}Installing Node.js...${NC}"
curl -fsSL https://deb.nodesource.com/setup_20.x | bash -
apt install -y -qq nodejs
echo -e "${GREEN}✓ Node.js installed${NC}"
echo ""

# Install Nginx for TLS
echo -e "${BLUE}Installing Nginx...${NC}"
apt install -y -qq nginx openssl
ufw allow 443/tcp
echo -e "${GREEN}✓ Nginx installed${NC}"
echo ""

# Install Squid for HTTP Proxy
echo -e "${BLUE}Installing Squid (HTTP Proxy)...${NC}"
apt install -y -qq squid
ufw allow 3128/tcp
echo -e "${GREEN}✓ Squid installed${NC}"
echo ""

# Install Dante for SOCKS5
echo -e "${BLUE}Installing Dante (SOCKS5)...${NC}"
apt install -y -qq dante-server
ufw allow 1080/tcp
echo -e "${GREEN}✓ Dante installed${NC}"
echo ""

# Install WireGuard
echo -e "${BLUE}Installing WireGuard...${NC}"
apt install -y -qq wireguard
ufw allow 51820/udp
echo -e "${GREEN}✓ WireGuard installed${NC}"
echo ""

# Configure Nginx for TLS
echo -e "${BLUE}Configuring Nginx...${NC}"
cat > /etc/nginx/sites-available/tunnel << 'EOF'
server {
    listen 443 ssl http2;
    server_name _;

    ssl_certificate /etc/ssl/certs/selfsigned.crt;
    ssl_certificate_key /etc/ssl/private/selfsigned.key;
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_ciphers HIGH:!aNULL:!MD5;

    location / {
        return 200 "Advanced Tunnel Server\n";
        add_header Content-Type text/plain;
    }
}
EOF

# Generate self-signed certificate (replace with Let's Encrypt in production)
openssl req -x509 -nodes -days 365 -newkey rsa:2048 \
    -keyout /etc/ssl/private/selfsigned.key \
    -out /etc/ssl/certs/selfsigned.crt \
    -subj "/C=US/ST=State/L=City/O=Company/CN=$SERVER_IP" > /dev/null 2>&1

ln -sf /etc/nginx/sites-available/tunnel /etc/nginx/sites-enabled/tunnel
rm -f /etc/nginx/sites-enabled/default
systemctl restart nginx
echo -e "${GREEN}✓ Nginx configured${NC}"
echo ""

# Configure Squid
echo -e "${BLUE}Configuring Squid...${NC}"
cat > /etc/squid/squid.conf << 'EOF'
http_port 3128
visible_hostname proxy

acl localnet src 0.0.0.1-0.255.255.255
acl localnet src 10.0.0.0/8
acl localnet src 172.16.0.0/12
acl localnet src 192.168.0.0/16

acl SSL_ports port 443
acl Safe_ports port 80
acl Safe_ports port 443
acl CONNECT method CONNECT

http_access allow CONNECT SSL_ports
http_access allow localnet
http_access deny all

forwarded_for transparent
via off
EOF

systemctl restart squid
echo -e "${GREEN}✓ Squid configured${NC}"
echo ""

# Configure Dante
echo -e "${BLUE}Configuring Dante...${NC}"
cat > /etc/danted.conf << 'EOF'
logoutput: syslog
user.privileged: root
user.unprivileged: nobody

internal: 0.0.0.0 port = 1080
external: eth0

method: none
clientmethod: none

client pass {
    from: 0.0.0.0/0 to: 0.0.0.0/0
    log: connect disconnect error
}

pass {
    from: 0.0.0.0/0 to: 0.0.0.0/0
    protocol: tcp udp
    log: connect disconnect error
}
EOF

systemctl restart danted
echo -e "${GREEN}✓ Dante configured${NC}"
echo ""

# Configure WireGuard
echo -e "${BLUE}Configuring WireGuard...${NC}"

# Generate server keys
SERVER_PRIV=$(wg genkey)
SERVER_PUB=$(echo $SERVER_PRIV | wg pubkey)

# Generate client keys
CLIENT_PRIV=$(wg genkey)
CLIENT_PUB=$(echo $CLIENT_PRIV | wg pubkey)
PRESHARED=$(wg genpsk)

# Create WireGuard config
cat > /etc/wireguard/wg0.conf << EOF
[Interface]
PrivateKey = $SERVER_PRIV
Address = 10.66.66.1/24
ListenPort = 51820
MTU = 1420

PostUp = iptables -A FORWARD -i %i -j ACCEPT; iptables -t nat -A POSTROUTING -o eth0 -j MASQUERADE
PostDown = iptables -D FORWARD -i %i -j ACCEPT; iptables -t nat -D POSTROUTING -o eth0 -j MASQUERADE

[Peer]
PublicKey = $CLIENT_PUB
PresharedKey = $PRESHARED
AllowedIPs = 10.66.66.2/32
EOF

# Enable IP forwarding
echo "net.ipv4.ip_forward=1" >> /etc/sysctl.conf
sysctl -p

# Start WireGuard
systemctl enable wg-quick@wg0
systemctl start wg-quick@wg0
echo -e "${GREEN}✓ WireGuard configured${NC}"
echo ""

# Create WebSocket server
echo -e "${BLUE}Configuring WebSocket server...${NC}"
cat > /opt/websocket-server.js << 'EOF'
const WebSocket = require('ws');
const wss = new WebSocket.Server({ port: 8080 });

wss.on('connection', (ws) => {
  console.log('Client connected');
  
  ws.on('message', (message) => {
    console.log('Received:', message);
    ws.send(message);
  });
  
  ws.on('close', () => {
    console.log('Client disconnected');
  });
});

console.log('WebSocket server running on ws://0.0.0.0:8080');
EOF

# Install dependencies and start
cd /opt
npm init -y > /dev/null 2>&1
npm install ws > /dev/null 2>&1
nohup node websocket-server.js > /var/log/websocket.log 2>&1 &
ufw allow 8080/tcp
echo -e "${GREEN}✓ WebSocket server configured${NC}"
echo ""

# Configure firewall
echo -e "${BLUE}Configuring firewall...${NC}"
ufw allow 22/tcp
ufw allow 80/tcp
ufw allow 443/tcp
ufw allow 8080/tcp
ufw allow 3128/tcp
ufw allow 1080/tcp
ufw allow 51820/udp
ufw --force enable
echo -e "${GREEN}✓ Firewall configured${NC}"
echo ""

# Configure fail2ban
echo -e "${BLUE}Configuring fail2ban...${NC}"
systemctl enable fail2ban
systemctl start fail2ban
echo -e "${GREEN}✓ fail2ban configured${NC}"
echo ""

# Configure automatic updates
echo -e "${BLUE}Configuring automatic updates...${NC}"
cat > /etc/apt/apt.conf.d/20auto-upgrades << 'EOF'
APT::Periodic::Update-Package-Lists "1";
APT::Periodic::Unattended-Upgrade "1";
APT::Periodic::Download-Upgradeable-Packages "1";
APT::Periodic::AutocleanInterval "7";
EOF
systemctl enable unattended-upgrades
systemctl start unattended-upgrades
echo -e "${GREEN}✓ Automatic updates configured${NC}"
echo ""

# Display completion message
echo -e "${BLUE}"
echo "=========================================="
echo "  Setup Complete!"
echo "=========================================="
echo -e "${NC}"
echo ""
echo -e "Your server is now configured with the following services:"
echo ""
echo -e "  ${GREEN}✓ TLS Server${NC}         - Port 443 (HTTPS)"
echo -e "  ${GREEN}✓ WebSocket Server${NC}  - Port 8080"
echo -e "  ${GREEN}✓ HTTP Proxy${NC}        - Port 3128 (Squid)"
echo -e "  ${GREEN}✓ SOCKS5 Proxy${NC}      - Port 1080 (Dante)"
echo -e "  ${GREEN}✓ WireGuard VPN${NC}      - Port 51820 (UDP)"
echo ""
echo -e "Server IP: ${YELLOW}$SERVER_IP${NC}"
echo ""
echo -e "${BLUE}WireGuard Configuration:${NC}"
echo "  Server Private Key: $SERVER_PRIV"
echo "  Server Public Key:  $SERVER_PUB"
echo "  Client Private Key: $CLIENT_PRIV"
echo "  Client Public Key:  $CLIENT_PUB"
echo "  Preshared Key:      $PRESHARED"
echo ""
echo -e "${YELLOW}Save these keys! They won't be displayed again.${NC}"
echo ""
echo -e "${BLUE}Client Configuration for Advanced Tunnel:${NC}"
echo ""
echo "  Protocol: tls"
echo "  Server: $SERVER_IP"
echo "  Port: 443"
echo "  SNI: $SERVER_IP"
echo ""
echo "  OR"
echo ""
echo "  Protocol: websocket"
echo "  Server: $SERVER_IP"
echo "  Port: 8080"
echo ""
echo "  OR"
echo ""
echo "  Protocol: http-connect"
echo "  Server: $SERVER_IP"
echo "  Port: 3128"
echo ""
echo "  OR"
echo ""
echo "  Protocol: socks5"
echo "  Server: $SERVER_IP"
echo "  Port: 1080"
echo ""
echo "  OR"
echo ""
echo "  Protocol: wireguard"
echo "  Private Key: $CLIENT_PRIV"
echo "  Address: 10.66.66.2/32"
echo "  DNS: 10.66.66.1"
echo "  Server Public Key: $SERVER_PUB"
echo "  Preshared Key: $PRESHARED"
echo "  Endpoint: $SERVER_IP:51820"
echo "  Allowed IPs: 0.0.0.0/0, ::/0"
echo ""
echo -e "${BLUE}Next Steps:${NC}"
echo "  1. For production, replace self-signed cert with Let's Encrypt"
echo "  2. Test each service: curl -x socks5://$SERVER_IP:1080 http://example.com"
echo "  3. Configure Advanced Tunnel app with your server details"
echo "  4. Monitor services: sudo systemctl status nginx squid danted wg-quick@wg0"
echo ""
echo -e "${GREEN}Server setup complete!${NC}"
