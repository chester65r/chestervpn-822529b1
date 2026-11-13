/**
 * Structured append-only audit logging for MCP tools, relay operations, and admin access.
 */
export type AuditEvent = {
  id: string;
  timestamp: number;
  actorId: string;
  action: string;
  target: string;
  status: "success" | "failure" | "unauthorized";
  details?: Record<string, unknown>;
};

const auditLogStore: AuditEvent[] = [];

export function recordAuditEvent(event: Omit<AuditEvent, "id" | "timestamp">): AuditEvent {
  const record: AuditEvent = {
    ...event,
    id: `audit-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`,
    timestamp: Date.now(),
  };
  auditLogStore.push(record);
  // Keep last 1000 events in memory
  if (auditLogStore.length > 1000) {
    auditLogStore.shift();
  }
  return record;
}

export function getAuditLogs(): AuditEvent[] {
  return [...auditLogStore];
}

export function clearAuditLogsForTesting(): void {
  auditLogStore.length = 0;
}
