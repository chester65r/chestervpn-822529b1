import { useCallback, useEffect, useState } from "react";
import { Activity, CheckCircle2, Clock, Globe2, Plug, RefreshCw, Server, XCircle, Wifi } from "lucide-react";
import { toast } from "sonner";

import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Progress } from "@/components/ui/progress";
import {
  tunnelManager,
  dnsManager,
  connectionTester,
  type TunnelConfig,
  type TunnelConnection,
  type ConnectionTestResult,
  createDefaultConfig,
  DEFAULT_PORTS,
} from "@/lib/tunnel-engine";

// Sample test servers for diagnostics
const TEST_SERVERS = [
  { host: "google.com", port: 443, protocol: "tls" as const },
  { host: "cloudflare.com", port: 443, protocol: "tls" as const },
  { host: "1.1.1.1", port: 443, protocol: "tls" as const },
  { host: "8.8.8.8", port: 53, protocol: "tls" as const },
];

export function NetworkDiagnosticsTab() {
  const [connections, setConnections] = useState<Map<string, TunnelConnection>>(new Map());
  const [testResults, setTestResults] = useState<ConnectionTestResult[]>([]);
  const [dnsStatus, setDnsStatus] = useState<"idle" | "testing" | "success" | "error">("idle");
  const [isTesting, setIsTesting] = useState(false);
  const [activeConnection, setActiveConnection] = useState<TunnelConnection | null>(null);

  // Subscribe to tunnel manager updates
  useEffect(() => {
    const unsubscribe = tunnelManager.subscribe((conns) => {
      setConnections(conns);
    });
    
    return () => unsubscribe();
  }, []);

  // Load initial connections
  useEffect(() => {
    setConnections(tunnelManager.getConnections());
  }, []);

  // Test DNS connectivity
  const testDNS = useCallback(async () => {
    setDnsStatus("testing");
    try {
      const isWorking = await dnsManager.testDNS();
      setDnsStatus(isWorking ? "success" : "error");
      if (!isWorking) {
        toast.error("DNS resolution failed");
      }
    } catch (error) {
      setDnsStatus("error");
      toast.error("DNS test error", { description: String(error) });
    }
  }, []);

  // Test all connections
  const testAllConnections = useCallback(async () => {
    setIsTesting(true);
    setTestResults([]);
    
    try {
      const results = await connectionTester.testMultiple(TEST_SERVERS);
      setTestResults(results);
      
      // Check if any tests failed
      const failed = results.filter((r) => !r.success);
      if (failed.length > 0) {
        toast.warning(`${failed.length} connection tests failed`);
      } else {
        toast.success("All connection tests passed");
      }
    } catch (error) {
      toast.error("Connection test error", { description: String(error) });
    } finally {
      setIsTesting(false);
    }
  }, []);

  // Create a test connection
  const createTestConnection = useCallback(async () => {
    const config = createDefaultConfig("tls");
    config.host = "example.com";
    config.port = 443;
    config.name = "Test Connection";
    
    try {
      const connection = await tunnelManager.connect(config);
      setActiveConnection(connection);
      toast.success("Test connection established");
    } catch (error) {
      toast.error("Connection failed", { description: String(error) });
    }
  }, []);

  // Disconnect
  const disconnect = useCallback(() => {
    if (activeConnection) {
      tunnelManager.disconnect(activeConnection.id);
      setActiveConnection(null);
      toast.success("Disconnected");
    }
  }, [activeConnection]);

  // Format bytes
  const formatBytes = (bytes: number) => {
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(2)} KB`;
    return `${(bytes / (1024 * 1024)).toFixed(2)} MB`;
  };

  // Format latency
  const formatLatency = (ms: number) => {
    if (ms < 1) return "<1ms";
    if (ms < 1000) return `${ms}ms`;
    return `${(ms / 1000).toFixed(2)}s`;
  };

  // Get connection status color
  const getStatusColor = (status: TunnelConnection["status"]) => {
    switch (status) {
      case "connected":
        return "text-green-500";
      case "connecting":
        return "text-blue-500";
      case "disconnected":
        return "text-muted-foreground";
      case "error":
        return "text-red-500";
      default:
        return "text-muted-foreground";
    }
  };

  // Get test result icon
  const getTestResultIcon = (success: boolean) => {
    return success ? <CheckCircle2 className="h-4 w-4 text-green-500" /> : <XCircle className="h-4 w-4 text-red-500" />;
  };

  return (
    <div className="space-y-6">
      {/* Quick Actions */}
      <div className="flex flex-wrap gap-3">
        <Button onClick={testDNS} disabled={dnsStatus === "testing"} variant="outline">
          <Globe2 className="h-4 w-4 mr-2" />
          {dnsStatus === "testing" ? "Testing DNS..." : "Test DNS"}
        </Button>
        <Button onClick={testAllConnections} disabled={isTesting}>
          <Wifi className="h-4 w-4 mr-2" />
          {isTesting ? "Testing..." : "Test All Connections"}
        </Button>
        <Button onClick={createTestConnection} disabled={activeConnection !== null}>
          <Server className="h-4 w-4 mr-2" />
          Create Test Connection
        </Button>
        {activeConnection && (
          <Button onClick={disconnect} variant="destructive">
            <Plug className="h-4 w-4 mr-2" />
            Disconnect
          </Button>
        )}
      </div>

      {/* Active Connection Status */}
      {activeConnection && (
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <Activity className="h-5 w-5" />
              Active Connection
            </CardTitle>
            <CardDescription>
              Real-time connection status
            </CardDescription>
          </CardHeader>
          <CardContent>
            <div className="space-y-4">
              <div className="flex items-center justify-between">
                <span className="text-muted-foreground">Status</span>
                <span className={`flex items-center gap-1 font-medium ${getStatusColor(activeConnection.status)}`}>
                  {activeConnection.status === "connecting" && (
                    <RefreshCw className="h-4 w-4 animate-spin" />
                  )}
                  {activeConnection.status}
                </span>
              </div>
              <div className="flex items-center justify-between">
                <span className="text-muted-foreground">Protocol</span>
                <span className="font-medium">{activeConnection.config.protocol}</span>
              </div>
              <div className="flex items-center justify-between">
                <span className="text-muted-foreground">Server</span>
                <span className="font-mono text-sm">{activeConnection.config.host}:{activeConnection.config.port}</span>
              </div>
              <div className="flex items-center justify-between">
                <span className="text-muted-foreground">Uptime</span>
                <span className="font-medium">
                  {((Date.now() - activeConnection.startedAt) / 1000).toFixed(1)}s
                </span>
              </div>
              <div className="flex items-center justify-between">
                <span className="text-muted-foreground">Data</span>
                <span className="font-medium">
                  ↑ {formatBytes(activeConnection.bytesSent)} / ↓ {formatBytes(activeConnection.bytesReceived)}
                </span>
              </div>
            </div>
            {activeConnection.error && (
              <div className="mt-4 rounded-lg border border-destructive bg-destructive/10 p-3 text-sm text-destructive">
                Error: {activeConnection.error}
              </div>
            )}
          </CardContent>
        </Card>
      )}

      {/* DNS Status */}
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <Globe2 className="h-5 w-5" />
            DNS Resolution
          </CardTitle>
          <CardDescription>
            Test DNS connectivity and resolution
          </CardDescription>
        </CardHeader>
        <CardContent>
          <div className="flex items-center justify-between">
            <span className="text-muted-foreground">DNS Status</span>
            <span className={`flex items-center gap-1 font-medium ${
              dnsStatus === "success" ? "text-green-500" : 
              dnsStatus === "error" ? "text-red-500" : 
              "text-muted-foreground"
            }`}>
              {dnsStatus === "testing" && <RefreshCw className="h-4 w-4 animate-spin" />}
              {dnsStatus === "success" && <CheckCircle2 className="h-4 w-4" />}
              {dnsStatus === "error" && <XCircle className="h-4 w-4" />}
              {dnsStatus}
            </span>
          </div>
          <div className="mt-4">
            <p className="text-xs text-muted-foreground mb-2">Configured DNS Servers</p>
            <div className="flex flex-wrap gap-2">
              {dnsManager.getConfig().servers.map((server) => (
                <span key={server} className="rounded-full border border-border bg-muted px-3 py-1 text-xs font-mono">
                  {server}
                </span>
              ))}
            </div>
          </div>
        </CardContent>
      </Card>

      {/* Connection Tests */}
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <Wifi className="h-5 w-5" />
            Connection Tests
          </CardTitle>
          <CardDescription>
            Test connectivity to common servers
          </CardDescription>
        </CardHeader>
        <CardContent>
          {testResults.length === 0 ? (
            <div className="flex flex-col items-center justify-center py-8 text-center text-muted-foreground">
              <Clock className="h-8 w-8 mb-2" />
              <p className="text-sm">No tests run yet</p>
              <p className="text-xs mt-1">Click "Test All Connections" to start</p>
            </div>
          ) : (
            <div className="space-y-3">
              {testResults.map((result, index) => (
                <div
                  key={`${result.host}-${result.port}`}
                  className="flex items-center justify-between rounded-lg border border-border p-3"
                >
                  <div className="flex items-center gap-3">
                    {getTestResultIcon(result.success)}
                    <div>
                      <p className="font-medium">{result.host}:{result.port}</p>
                      <p className="text-xs text-muted-foreground">{result.protocol}</p>
                    </div>
                  </div>
                  <div className="flex items-center gap-3">
                    <span className={`text-xs font-medium ${
                      result.success ? "text-green-500" : "text-red-500"
                    }`}>
                      {result.success ? "Success" : "Failed"}
                    </span>
                    <span className="text-xs text-muted-foreground">
                      {formatLatency(result.latency)}
                    </span>
                  </div>
                </div>
              ))}
            </div>
          )}
        </CardContent>
      </Card>

      {/* Active Connections List */}
      {connections.size > 0 && (
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <Server className="h-5 w-5" />
              Active Connections ({connections.size})
            </CardTitle>
            <CardDescription>
              All established tunnel connections
            </CardDescription>
          </CardHeader>
          <CardContent>
            <div className="space-y-3">
              {Array.from(connections.values()).map((conn) => (
                <div
                  key={conn.id}
                  className="flex items-center justify-between rounded-lg border border-border p-3"
                >
                  <div className="flex items-center gap-3">
                    <span className={`h-2 w-2 rounded-full ${
                      conn.status === "connected" ? "bg-green-500" :
                      conn.status === "connecting" ? "bg-blue-500" :
                      conn.status === "error" ? "bg-red-500" :
                      "bg-muted-foreground"
                    }`} />
                    <div>
                      <p className="font-medium">{conn.config.name}</p>
                      <p className="text-xs text-muted-foreground">
                        {conn.config.protocol}://{conn.config.host}:{conn.config.port}
                      </p>
                    </div>
                  </div>
                  <div className="flex items-center gap-3">
                    <span className={`text-xs font-medium ${getStatusColor(conn.status)}`}>
                      {conn.status}
                    </span>
                    <button
                      onClick={() => tunnelManager.disconnect(conn.id)}
                      className="text-xs text-muted-foreground hover:text-foreground"
                    >
                      Disconnect
                    </button>
                  </div>
                </div>
              ))}
            </div>
          </CardContent>
        </Card>
      )}

      {/* Network Statistics */}
      {activeConnection && (
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <Activity className="h-5 w-5" />
              Network Statistics
            </CardTitle>
            <CardDescription>
              Real-time connection metrics
            </CardDescription>
          </CardHeader>
          <CardContent>
            <div className="space-y-4">
              <div>
                <p className="text-xs text-muted-foreground mb-1">Connection Stability</p>
                <Progress value={100} className="h-2" />
                <p className="text-xs text-muted-foreground mt-1">100% stable</p>
              </div>
              <div className="grid grid-cols-2 gap-4">
                <div>
                  <p className="text-xs text-muted-foreground mb-1">Upload</p>
                  <p className="text-lg font-semibold">{formatBytes(activeConnection.bytesSent)}/s</p>
                </div>
                <div>
                  <p className="text-xs text-muted-foreground mb-1">Download</p>
                  <p className="text-lg font-semibold">{formatBytes(activeConnection.bytesReceived)}/s</p>
                </div>
              </div>
            </div>
          </CardContent>
        </Card>
      )}
    </div>
  );
}
