import fs from "node:fs";
import net from "node:net";
import tls from "node:tls";
import crypto from "node:crypto";

const TLS_CERT_PATH = process.env.TLS_CERT_PATH || "/etc/tunnel-relay/tls/fullchain.pem";
const TLS_KEY_PATH = process.env.TLS_KEY_PATH || "/etc/tunnel-relay/tls/privkey.pem";
const TLS_PORTS = (process.env.TLS_PORTS || "443,8443")
  .split(",")
  .map((p) => Number(p.trim()))
  .filter((p) => p > 0);
const HTTP_PORTS = (process.env.HTTP_PORTS || "80,8080")
  .split(",")
  .map((p) => Number(p.trim()))
  .filter((p) => p > 0);
const SOCKS5_PORT = Number(process.env.SOCKS5_PORT || 1080);

const startedAt = Date.now();
let activeConnections = 0;

/**
 * Computes the RFC 6455 Sec-WebSocket-Accept header value when a WebSocket client key is supplied.
 */
function computeWebSocketAccept(secWebSocketKey) {
  return crypto
    .createHash("sha1")
    .update(`${secWebSocketKey.trim()}258EAFA5-E914-47DA-95CA-C5AB0DC85B11`)
    .digest("base64");
}

/**
 * Strips leading KeepAliveManager "\r\n" heartbeats and routes either:
 * - Outbound TCP proxy stream (when Target-Host / X-Target / CONNECT destination is specified)
 * - Echo/NAT tunnel keepalive & packet stream for TunnelVpnService
 */
function attachTunnelStream(clientSocket, initialRemainder, targetHost, targetPort) {
  activeConnections += 1;
  clientSocket.setNoDelay(true);
  clientSocket.setKeepAlive(true, 15_000);

  let upstream = null;
  if (targetHost && targetPort) {
    upstream = net.connect({ host: targetHost, port: targetPort }, () => {
      if (initialRemainder && initialRemainder.length > 0) {
        upstream.write(initialRemainder);
      }
    });
    upstream.setNoDelay(true);
    upstream.on("data", (chunk) => {
      if (!clientSocket.destroyed) clientSocket.write(chunk);
    });
    upstream.on("error", () => {
      if (!clientSocket.destroyed) clientSocket.destroy();
    });
    upstream.on("close", () => {
      if (!clientSocket.destroyed) clientSocket.end();
    });
  }

  clientSocket.on("data", (chunk) => {
    // Ignore standalone KeepAliveManager "\r\n" heartbeats
    if (chunk.length === 2 && chunk[0] === 0x0d && chunk[1] === 0x0a) {
      return;
    }

    if (upstream && !upstream.destroyed) {
      upstream.write(chunk);
      return;
    }

    // Parse embedded IPv4 packet destination (version=4, IHL>=5) when running as raw packet tunnel
    if (chunk.length >= 20 && (chunk[0] >> 4) === 4) {
      const proto = chunk[9];
      const dstIp = `${chunk[16]}.${chunk[17]}.${chunk[18]}.${chunk[19]}`;
      const ihl = (chunk[0] & 0x0f) * 4;
      if (proto === 6 && chunk.length >= ihl + 4) {
        const dstPort = chunk.readUInt16BE(ihl + 2);
        if (!upstream && dstPort > 0) {
          upstream = net.connect({ host: dstIp, port: dstPort });
          upstream.on("error", () => {});
        }
      }
    }
  });

  const cleanup = () => {
    activeConnections = Math.max(0, activeConnections - 1);
    if (upstream && !upstream.destroyed) upstream.destroy();
  };

  clientSocket.on("close", cleanup);
  clientSocket.on("error", cleanup);
}

/**
 * Handles incoming raw or TLS-wrapped connections from:
 * 1. Control-plane health check (`HEAD /health` or `GET /health`)
 * 2. `uTLSEvasionEngine` fragmented HTTP Custom Payload / WebSocket Upgrade
 * 3. HTTP `CONNECT host:port` tunnel requests
 */
function handleClientConnection(socket, transportLabel) {
  let headerBuf = Buffer.alloc(0);
  let handshakeCompleted = false;

  const onData = (chunk) => {
    if (handshakeCompleted) return;
    headerBuf = Buffer.concat([headerBuf, chunk]);

    // Wait until end of HTTP headers (\r\n\r\n) or cap at 16 KB
    const headerEndIndex = headerBuf.indexOf("\r\n\r\n");
    if (headerEndIndex === -1) {
      if (headerBuf.length > 16_384) {
        socket.destroy();
      }
      return;
    }

    handshakeCompleted = true;
    socket.removeListener("data", onData);

    const rawHeaderBlock = headerBuf.subarray(0, headerEndIndex).toString("utf8");
    const remainder = headerBuf.subarray(headerEndIndex + 4);
    const lines = rawHeaderBlock.split("\r\n").filter((l) => l.trim().length > 0);
    const requestLine = lines[0] || "";
    const [method = "", requestTarget = ""] = requestLine.split(" ");

    const headers = {};
    for (let i = 1; i < lines.length; i++) {
      const idx = lines[i].indexOf(":");
      if (idx > 0) {
        const key = lines[i].slice(0, idx).trim().toLowerCase();
        const val = lines[i].slice(idx + 1).trim();
        headers[key] = val;
      }
    }

    // 1. Control-plane health check endpoint (`HEAD /health` or `GET /health`)
    if ((method === "HEAD" || method === "GET") && requestTarget.startsWith("/health")) {
      const body = JSON.stringify({
        status: "online",
        transport: transportLabel,
        activeConnections,
        uptimeSeconds: Math.round((Date.now() - startedAt) / 1000),
        transports: ["wireguard", "tls-sni", "websocket", "http-custom-payload", "socks5", "ssh"],
      });
      const responseHeaders = [
        "HTTP/1.1 200 OK",
        "Content-Type: application/json",
        `Content-Length: ${Buffer.byteLength(body)}`,
        "Cache-Control: no-store",
        "Connection: close",
        "\r\n",
      ].join("\r\n");
      socket.write(method === "HEAD" ? responseHeaders : responseHeaders + body);
      socket.end();
      return;
    }

    // 2. HTTP CONNECT proxy request (`CONNECT host:port HTTP/1.1`)
    if (method === "CONNECT") {
      const [connectHost, connectPortStr] = requestTarget.split(":");
      const connectPort = Number(connectPortStr) || 443;
      socket.write("HTTP/1.1 200 Connection Established\r\nProxy-Agent: TunnelConsole-Relay/1.0\r\n\r\n");
      attachTunnelStream(socket, remainder, connectHost, connectPort);
      return;
    }

    // 3. HTTP Custom Payload / WebSocket Upgrade (`GET / HTTP/1.1` + `Upgrade: websocket`)
    const wsKey = headers["sec-websocket-key"];
    const customTarget = headers["x-target-host"] || headers["x-forward-to"] || "";
    let targetHost = null;
    let targetPort = null;
    if (customTarget.includes(":")) {
      const parts = customTarget.split(":");
      targetHost = parts[0];
      targetPort = Number(parts[1]) || 443;
    }

    const upgradeHeaders = [
      "HTTP/1.1 101 Switching Protocols",
      "Upgrade: websocket",
      "Connection: Upgrade",
      "X-Tunnel-Transport: " + transportLabel,
    ];
    if (wsKey) {
      upgradeHeaders.push(`Sec-WebSocket-Accept: ${computeWebSocketAccept(wsKey)}`);
    }
    upgradeHeaders.push("\r\n");

    socket.write(upgradeHeaders.join("\r\n"));
    attachTunnelStream(socket, remainder, targetHost, targetPort);
  };

  socket.on("data", onData);
  socket.on("error", () => {});
}

/**
 * Starts RFC 1928 SOCKS5 server on SOCKS5_PORT so SOCKS5 transport clients have full internet access.
 */
function startSocks5Server(port) {
  const server = net.createServer((client) => {
    client.once("data", (greeting) => {
      if (!greeting || greeting[0] !== 0x05) {
        client.destroy();
        return;
      }
      // No-auth response (0x05 0x00)
      client.write(Buffer.from([0x05, 0x00]));

      client.once("data", (req) => {
        if (!req || req.length < 7 || req[0] !== 0x05 || req[1] !== 0x01) {
          client.destroy();
          return;
        }
        const atyp = req[3];
        let dstHost = "";
        let dstPort = 0;

        if (atyp === 0x01 && req.length >= 10) {
          dstHost = `${req[4]}.${req[5]}.${req[6]}.${req[7]}`;
          dstPort = req.readUInt16BE(8);
        } else if (atyp === 0x03) {
          const domainLen = req[4];
          if (req.length < 5 + domainLen + 2) {
            client.destroy();
            return;
          }
          dstHost = req.subarray(5, 5 + domainLen).toString("utf8");
          dstPort = req.readUInt16BE(5 + domainLen);
        } else {
          client.destroy();
          return;
        }

        const remote = net.connect({ host: dstHost, port: dstPort }, () => {
          // SOCKS5 success reply
          client.write(Buffer.from([0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0]));
          client.pipe(remote);
          remote.pipe(client);
        });

        remote.on("error", () => client.destroy());
        client.on("error", () => remote.destroy());
      });
    });
  });

  server.listen(port, "0.0.0.0", () => {
    console.log(`[tunnel-relay] SOCKS5 bridge listening on 0.0.0.0:${port}`);
  });
}

// Start HTTP Custom Payload + Corridor Probe listeners (ports 80, 8080)
for (const port of HTTP_PORTS) {
  const httpServer = net.createServer((socket) => handleClientConnection(socket, `http-payload:${port}`));
  httpServer.listen(port, "0.0.0.0", () => {
    console.log(`[tunnel-relay] HTTP Custom Payload & Corridor Probe listening on 0.0.0.0:${port}`);
  });
}

// Start TLS + SNI + WebSocket + HTTP Custom Payload listeners (ports 443, 8443)
if (fs.existsSync(TLS_CERT_PATH) && fs.existsSync(TLS_KEY_PATH)) {
  const cert = fs.readFileSync(TLS_CERT_PATH);
  const key = fs.readFileSync(TLS_KEY_PATH);
  const defaultSecureContext = tls.createSecureContext({ cert, key });

  for (const port of TLS_PORTS) {
    const tlsServer = tls.createServer(
      {
        cert,
        key,
        // Accept any custom SNI hostname sent by TunnelVpnService / uTLSEvasionEngine
        SNICallback: (_servername, cb) => cb(null, defaultSecureContext),
      },
      (tlsSocket) => handleClientConnection(tlsSocket, `tls-sni:${port}`)
    );
    tlsServer.listen(port, "0.0.0.0", () => {
      console.log(`[tunnel-relay] TLS + SNI + WebSocket gateway listening on 0.0.0.0:${port}`);
    });
  }
} else {
  console.warn("[tunnel-relay] TLS certificate not found; skipping TLS listeners.");
}

// Start SOCKS5 listener (port 1080)
if (SOCKS5_PORT > 0) {
  startSocks5Server(SOCKS5_PORT);
}
