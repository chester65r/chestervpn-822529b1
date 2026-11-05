# Server Setup Guide

## 📋 Overview

This guide helps you set up **your own real servers** for use with Advanced Tunnel VPN. All configurations are for **legitimate use only** with infrastructure you own or have permission to use.

---

## 🚀 Quick Start

### Option 1: One-Command Setup (Recommended)

Run this on a fresh Ubuntu 22.04 VPS:

```bash
# Install dependencies
sudo apt update && sudo apt upgrade -y
sudo apt install -y curl wget git net-tools iproute2 iptables

# Download and run setup script
curl -fsSL https://raw.githubusercontent.com/chester65r/chestervpn-822529b1/main/docs/setup-server.sh | bash
```

### Option 2: Manual Setup

Follow the detailed instructions below for each server type.

---

## 🔒 TLS Server Setup (Nginx)

### Install Nginx

```bash
sudo apt update
sudo apt install -y nginx openssl
```

### Configure Nginx for TLS

Create `/etc/nginx/sites-available/tunnel`:

```nginx
server {
    listen 443 ssl http2;
    server_name your-domain.com;

    ssl_certificate /etc/letsencrypt/live/your-domain.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/your-domain.com/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_ciphers HIGH:!aNULL:!MD5;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

### Get SSL Certificate (Let's Encrypt)

```bash
sudo apt install -y certbot python3-certbot-nginx
sudo certbot --nginx -d your-domain.com
```

### Test Configuration

```bash
sudo nginx -t
sudo systemctl restart nginx
```

### Client Configuration

In Advanced Tunnel app:
- **Server IP**: your-domain.com
- **Port**: 443
- **Protocol**: tls
- **SNI**: your-domain.com

---

## 🌐 WebSocket Server Setup (Node.js)

### Install Node.js

```bash
curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash -
sudo apt install -y nodejs
```

### Create WebSocket Server

Create `websocket-server.js`:

```javascript
const WebSocket = require('ws');
const net = require('net');

const wss = new WebSocket.Server({ port: 8080 });

wss.on('connection', (ws) => {
  console.log('Client connected');
  
  ws.on('message', (message) => {
    console.log('Received:', message);
    ws.send(`Echo: ${message}`);
  });
  
  ws.on('close', () => {
    console.log('Client disconnected');
  });
});

console.log('WebSocket server running on ws://0.0.0.0:8080');
```

### Install Dependencies

```bash
npm init -y
npm install ws
```

### Run Server

```bash
node websocket-server.js
```

### Run in Background

```bash
nohup node websocket-server.js > server.log 2>&1 &
```

### Client Configuration

In Advanced Tunnel app:
- **Server IP**: your-server-ip
- **Port**: 8080
- **Protocol**: websocket
- **Path**: / (or your custom path)

---

## 🔄 HTTP CONNECT Proxy Server

### Install Squid Proxy

```bash
sudo apt update
sudo apt install -y squid
```

### Configure Squid

Edit `/etc/squid/squid.conf`:

```conf
# Basic configuration
http_port 3128
visible_hostname proxy.your-domain.com

# Allow local access
acl localnet src 0.0.0.1-0.255.255.255
acl localnet src 10.0.0.0/8
acl localnet src 172.16.0.0/12
acl localnet src 192.168.0.0/16

# Allow CONNECT method
acl SSL_ports port 443
acl Safe_ports port 80
acl Safe_ports port 443
acl CONNECT method CONNECT

# Allow CONNECT to SSL ports
http_access allow CONNECT SSL_ports
http_access allow localnet
http_access deny all

# Enable forwarding
forwarded_for transparent
via off
```

### Restart Squid

```bash
sudo systemctl restart squid
sudo systemctl enable squid
```

### Client Configuration

In Advanced Tunnel app:
- **Server IP**: your-server-ip
- **Port**: 3128
- **Protocol**: http-connect

---

## 🔌 SOCKS5 Proxy Server

### Install Dante (SOCKS5 Server)

```bash
sudo apt update
sudo apt install -y dante-server
```

### Configure Dante

Edit `/etc/danted.conf`:

```conf
# Log configuration
logoutput: syslog
user.privileged: root
user.unprivileged: nobody

# Internal address
internal: 0.0.0.0 port = 1080

# External address
external: eth0

# Client access
method: username # or: none
clientmethod: none

# Client rules
client pass {
    from: 0.0.0.0/0 to: 0.0.0.0/0
    log: connect disconnect error
}

# Pass rules
pass {
    from: 0.0.0.0/0 to: 0.0.0.0/0
    protocol: tcp udp
    log: connect disconnect error
}
```

### Restart Dante

```bash
sudo systemctl restart danted
sudo systemctl enable danted
```

### Client Configuration

In Advanced Tunnel app:
- **Server IP**: your-server-ip
- **Port**: 1080
- **Protocol**: socks5

---

## 🔐 WireGuard VPN Server

### Install WireGuard

```bash
sudo apt update
sudo apt install -y wireguard
```

### Generate Keys

```bash
# Server private key
wg genkey | sudo tee /etc/wireguard/privatekey | wg pubkey | sudo tee /etc/wireguard/publickey

# Client private key (run on client or generate here)
wg genkey | tee client-privatekey | wg pubkey > client-publickey
```

### Configure WireGuard Server

Create `/etc/wireguard/wg0.conf`:

```conf
[Interface]
PrivateKey = <SERVER_PRIVATE_KEY>
Address = 10.66.66.1/24
ListenPort = 51820
MTU = 1420

# Enable NAT
PostUp = iptables -A FORWARD -i %i -j ACCEPT; iptables -t nat -A POSTROUTING -o eth0 -j MASQUERADE
PostDown = iptables -D FORWARD -i %i -j ACCEPT; iptables -t nat -D POSTROUTING -o eth0 -j MASQUERADE

[Peer]
# Client 1
PublicKey = <CLIENT_PUBLIC_KEY>
PresharedKey = <PRESHARED_KEY>
AllowedIPs = 10.66.66.2/32
```

### Enable IP Forwarding

Edit `/etc/sysctl.conf`:

```conf
net.ipv4.ip_forward=1
```

Apply changes:

```bash
sudo sysctl -p
```

### Start WireGuard

```bash
sudo systemctl enable wg-quick@wg0
sudo systemctl start wg-quick@wg0
```

### Check Status

```bash
sudo wg show
```

### Client Configuration

In Advanced Tunnel app (WireGuard mode):
- **Private Key**: <CLIENT_PRIVATE_KEY>
- **Address**: 10.66.66.2/32
- **DNS**: 10.66.66.1
- **Server Public Key**: <SERVER_PUBLIC_KEY>
- **Preshared Key**: <PRESHARED_KEY>
- **Endpoint**: your-server-ip:51820
- **Allowed IPs**: 0.0.0.0/0, ::/0
- **Keepalive**: 25

---

## 📊 Multi-Protocol Server (All-in-One)

For a server that supports all protocols on different ports:

### Install All Dependencies

```bash
sudo apt update
sudo apt install -y nginx nodejs npm squid dante-server wireguard
```

### Configure All Services

| Service | Port | Protocol | Config File |
|---------|------|----------|-------------|
| Nginx TLS | 443 | tls | /etc/nginx/sites-available/tunnel |
| WebSocket | 8080 | websocket | websocket-server.js |
| HTTP Proxy | 3128 | http-connect | /etc/squid/squid.conf |
| SOCKS5 | 1080 | socks5 | /etc/danted.conf |
| WireGuard | 51820 | wireguard | /etc/wireguard/wg0.conf |

### Start All Services

```bash
# Start Nginx
sudo systemctl restart nginx

# Start WebSocket
nohup node websocket-server.js > /var/log/websocket.log 2>&1 &

# Start Squid
sudo systemctl restart squid

# Start Dante
sudo systemctl restart danted

# Start WireGuard
sudo systemctl start wg-quick@wg0
```

---

## 🔍 Testing Your Server

### Test TLS Connection

```bash
openssl s_client -connect your-domain.com:443 -servername your-domain.com
```

### Test WebSocket Connection

```bash
wscat -c ws://your-server-ip:8080
```

### Test HTTP Proxy

```bash
curl -x http://your-server-ip:3128 http://example.com
```

### Test SOCKS5 Proxy

```bash
curl --socks5 your-server-ip:1080 http://example.com
```

### Test WireGuard

```bash
sudo wg-quick up wg0
ping 10.66.66.1
```

---

## 📝 Server Configuration Examples

### Example 1: Simple TLS Server (Nginx)

```nginx
server {
    listen 443 ssl;
    server_name vpn.your-domain.com;
    
    ssl_certificate /etc/letsencrypt/live/vpn.your-domain.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/vpn.your-domain.com/privkey.pem;
    
    location / {
        return 200 "OK\n";
        add_header Content-Type text/plain;
    }
}
```

### Example 2: WebSocket Echo Server

```javascript
const WebSocket = require('ws');
const wss = new WebSocket.Server({ port: 8080 });

wss.on('connection', (ws) => {
  ws.on('message', (message) => {
    ws.send(message); // Echo back
  });
});
```

### Example 3: Minimal SOCKS5 (Dante)

```conf
internal: 0.0.0.0 port = 1080
external: eth0
client pass { from: 0.0.0.0/0 to: 0.0.0.0/0 }
pass { from: 0.0.0.0/0 to: 0.0.0.0/0 protocol: tcp udp }
```

---

## 🛡️ Security Recommendations

### Firewall Configuration (UFW)

```bash
sudo ufw allow 22/tcp       # SSH
sudo ufw allow 80/tcp       # HTTP
sudo ufw allow 443/tcp      # HTTPS
sudo ufw allow 8080/tcp     # WebSocket
sudo ufw allow 3128/tcp     # HTTP Proxy
sudo ufw allow 1080/tcp     # SOCKS5
sudo ufw allow 51820/udp    # WireGuard
sudo ufw enable
```

### Fail2Ban (Prevent Brute Force)

```bash
sudo apt install -y fail2ban
sudo systemctl enable fail2ban
sudo systemctl start fail2ban
```

### Automatic Updates

```bash
sudo apt install -y unattended-upgrades
sudo dpkg-reconfigure -plow unattended-upgrades
```

---

## 📊 Monitoring Your Server

### Check Service Status

```bash
# Check all services
sudo systemctl status nginx squid danted wg-quick@wg0

# Check WireGuard
sudo wg show

# Check open ports
sudo ss -tulnp

# Check logs
sudo tail -f /var/log/nginx/error.log
sudo journalctl -u squid -f
sudo journalctl -u danted -f
```

### Install Monitoring Tools

```bash
# Install htop
sudo apt install -y htop

# Install net-tools
sudo apt install -y net-tools

# Install iftop (traffic monitoring)
sudo apt install -y iftop
```

---

## 🚨 Important Notes

1. **Use Strong Passwords** - For any authentication
2. **Keep Software Updated** - Regular security updates
3. **Monitor Logs** - Watch for suspicious activity
4. **Backup Configurations** - Save all config files
5. **Use SSL/TLS** - Always encrypt traffic
6. **Limit Access** - Use firewall rules
7. **Follow Local Laws** - Ensure compliance

---

## 📞 Support

For issues with server setup:
- Check logs: `/var/log/syslog`, `/var/log/nginx/error.log`
- Test connectivity: `ping`, `telnet`, `curl`
- Verify firewall: `sudo ufw status`
- Check service status: `sudo systemctl status <service>`

---

## 🎯 Next Steps

1. **Deploy a VPS** on DigitalOcean, AWS, Linode, or similar
2. **Choose a protocol** (TLS, WebSocket, HTTP Proxy, SOCKS5, or WireGuard)
3. **Follow the setup guide** for your chosen protocol
4. **Configure the client** in Advanced Tunnel app
5. **Test the connection**
6. **Monitor and maintain** your server

---

**⚠️ Remember:** Only use servers you own or have explicit permission to use. Do not use these configurations to bypass carrier restrictions or violate terms of service.
