/**
 * WebSocket Server for Advanced Tunnel
 * Run with: node websocket-server.js
 * For legitimate use only with servers you own
 */

const WebSocket = require('ws');
const http = require('http');
const url = require('url');

// Configuration
const PORT = process.env.PORT || 8080;
const HOST = process.env.HOST || '0.0.0.0';

// Create HTTP server for WebSocket
const server = http.createServer((req, res) => {
  res.writeHead(200, { 'Content-Type': 'text/plain' });
  res.end('WebSocket server is running\n');
});

// Create WebSocket server
const wss = new WebSocket.Server({ server, path: '/tunnel' });

// Connection tracking
const connections = new Set();
let totalBytesReceived = 0;
let totalBytesSent = 0;
let totalConnections = 0;

// WebSocket connection handler
wss.on('connection', (ws, req) => {
  totalConnections++;
  connections.add(ws);
  
  const clientIp = req.socket.remoteAddress;
  const clientPort = req.socket.remotePort;
  
  console.log(`[${new Date().toISOString()}] Client connected: ${clientIp}:${clientPort}`);
  
  // Send welcome message
  ws.send(JSON.stringify({
    type: 'welcome',
    message: 'Connected to Advanced Tunnel WebSocket Server',
    server: `${HOST}:${PORT}`,
    connections: connections.size,
    totalConnections
  }));
  
  // Message handler
  ws.on('message', (message) => {
    totalBytesReceived += message.length;
    
    // Log message (truncated for logging)
    const messageStr = message.toString().substring(0, 100);
    console.log(`[${new Date().toISOString()}] Received ${message.length} bytes: ${messageStr}...`);
    
    // Echo message back (or process it)
    try {
      const parsed = JSON.parse(message);
      if (parsed.type === 'ping') {
        ws.send(JSON.stringify({ type: 'pong', timestamp: Date.now() }));
      } else {
        // Echo back
        ws.send(message);
        totalBytesSent += message.length;
      }
    } catch (e) {
      // Not JSON, just echo
      ws.send(message);
      totalBytesSent += message.length;
    }
  });
  
  // Error handler
  ws.on('error', (error) => {
    console.error(`[${new Date().toISOString()}] WebSocket error:`, error);
  });
  
  // Close handler
  ws.on('close', () => {
    connections.delete(ws);
    console.log(`[${new Date().toISOString()}] Client disconnected: ${clientIp}:${clientPort}`);
  });
});

// HTTP server for health check
server.on('request', (req, res) => {
  const parsedUrl = url.parse(req.url, true);
  
  if (parsedUrl.pathname === '/health') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({
      status: 'running',
      connections: connections.size,
      totalConnections,
      bytesReceived: totalBytesReceived,
      bytesSent: totalBytesSent,
      uptime: process.uptime()
    }));
  } else {
    res.writeHead(200, { 'Content-Type': 'text/plain' });
    res.end('Advanced Tunnel WebSocket Server\n');
  }
});

// Start server
server.listen(PORT, HOST, () => {
  console.log(`WebSocket server running on ws://${HOST}:${PORT}/tunnel`);
  console.log(`HTTP health check at http://${HOST}:${PORT}/health`);
});

// Graceful shutdown
process.on('SIGTERM', () => {
  console.log('SIGTERM received, shutting down...');
  
  // Close all connections
  for (const ws of connections) {
    ws.close(1001, 'Server shutting down');
  }
  
  server.close(() => {
    console.log('Server closed');
    process.exit(0);
  });
});

process.on('SIGINT', () => {
  console.log('SIGINT received, shutting down...');
  process.emit('SIGTERM');
});

// Log statistics periodically
setInterval(() => {
  console.log(`[STATS] Connections: ${connections.size}, RX: ${totalBytesReceived}, TX: ${totalBytesSent}`);
}, 60000);
