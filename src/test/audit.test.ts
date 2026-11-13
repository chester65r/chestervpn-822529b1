// @vitest-environment node
import { describe, expect, it, beforeEach } from "vitest";
import { recordAuditEvent, getAuditLogs, clearAuditLogsForTesting } from "../lib/audit";

beforeEach(() => {
  clearAuditLogsForTesting();
});

describe("Structured Audit Logging", () => {
  it("records and retrieves audit events with generated ids and timestamps", () => {
    const event = recordAuditEvent({
      actorId: "user-123",
      action: "add-relay-server",
      target: "hetzner-cx23",
      status: "success",
      details: { host: "2.29.8.194" },
    });

    expect(event.id).toMatch(/^audit-/);
    expect(event.timestamp).toBeTypeOf("number");
    expect(event.status).toBe("success");

    const logs = getAuditLogs();
    expect(logs.length).toBe(1);
    expect(logs[0]?.target).toBe("hetzner-cx23");
  });
});
