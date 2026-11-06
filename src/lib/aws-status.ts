// Pure parsing helpers for read-only AWS EC2 responses (Query protocol XML).

export type AwsInstance = {
  id: string;
  name: string;
  state: string;
  type: string;
  publicIp: string | null;
  securityGroupIds: string[];
};

export type AwsSgRule = { protocol: string; from: number | null; to: number | null; cidrs: string[] };
export type AwsSecurityGroup = { id: string; name: string; inbound: AwsSgRule[] };

function blocks(xml: string, tag: string): string[] {
  const out: string[] = [];
  const open = `<${tag}>`;
  const close = `</${tag}>`;
  let i = 0;
  while ((i = xml.indexOf(open, i)) !== -1) {
    let depth = 1;
    let j = i + open.length;
    while (depth > 0) {
      const nextOpen = xml.indexOf(open, j);
      const nextClose = xml.indexOf(close, j);
      if (nextClose === -1) return out;
      if (nextOpen !== -1 && nextOpen < nextClose) {
        depth++;
        j = nextOpen + open.length;
      } else {
        depth--;
        j = nextClose + close.length;
      }
    }
    out.push(xml.slice(i + open.length, j - close.length));
    i = j;
  }
  return out;
}

function first(xml: string, tag: string): string | null {
  const m = xml.match(new RegExp(`<${tag}>([^<]*)</${tag}>`));
  return m ? m[1]! : null;
}

export function parseInstances(xml: string): AwsInstance[] {
  const result: AwsInstance[] = [];
  for (const set of blocks(xml, "instancesSet")) {
    for (const item of blocks(set, "item")) {
      const id = first(item, "instanceId");
      if (!id) continue;
      const stateBlock = blocks(item, "instanceState")[0] ?? "";
      const tags = blocks(item, "tagSet")[0] ?? "";
      const nameTag = blocks(tags, "item").find((t) => first(t, "key") === "Name");
      const groups = blocks(item, "groupSet")[0] ?? "";
      result.push({
        id,
        name: nameTag ? (first(nameTag, "value") ?? "") : "",
        state: first(stateBlock, "name") ?? "unknown",
        type: first(item, "instanceType") ?? "",
        publicIp: first(item, "ipAddress"),
        securityGroupIds: blocks(groups, "item").map((g) => first(g, "groupId")).filter((g): g is string => !!g),
      });
    }
  }
  return result;
}

export function parseSecurityGroups(xml: string): AwsSecurityGroup[] {
  const info = blocks(xml, "securityGroupInfo")[0] ?? "";
  return blocks(info, "item")
    .filter((g) => first(g, "groupId"))
    .map((g) => {
      const perms = blocks(g, "ipPermissions")[0] ?? "";
      return {
        id: first(g, "groupId")!,
        name: first(g, "groupName") ?? "",
        inbound: blocks(perms, "item")
          .filter((p) => first(p, "ipProtocol"))
          .map((p) => {
            const ranges = blocks(p, "ipRanges")[0] ?? "";
            const from = first(p, "fromPort");
            const to = first(p, "toPort");
            return {
              protocol: first(p, "ipProtocol")!,
              from: from == null ? null : Number(from),
              to: to == null ? null : Number(to),
              cidrs: blocks(ranges, "item").map((r) => first(r, "cidrIp")).filter((c): c is string => !!c),
            };
          }),
      };
    });
}

/** True when an inbound rule allows UDP on the given port. */
export function allowsUdpPort(rule: AwsSgRule, port: number): boolean {
  if (rule.protocol === "-1") return true;
  if (rule.protocol !== "udp" && rule.protocol !== "17") return false;
  return rule.from != null && rule.to != null && rule.from <= port && port <= rule.to;
}

export type SafetyWarning = { groupId: string; message: string };

/** Flags inbound rules open to the whole internet, except the WireGuard port. */
export function safetyWarnings(groups: AwsSecurityGroup[], wgPort = 51820): SafetyWarning[] {
  const out: SafetyWarning[] = [];
  for (const g of groups) {
    for (const r of g.inbound) {
      if (!r.cidrs.includes("0.0.0.0/0")) continue;
      if (r.protocol !== "-1" && allowsUdpPort(r, wgPort) && r.from === wgPort && r.to === wgPort) continue;
      const ports = r.protocol === "-1" ? "all traffic" : `${r.protocol.toUpperCase()} ${r.from === r.to ? r.from : `${r.from}-${r.to}`}`;
      out.push({ groupId: g.id, message: `${g.name || g.id}: ${ports} open to everyone` });
    }
  }
  return out;
}
