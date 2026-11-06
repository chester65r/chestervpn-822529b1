import { describe, expect, it } from "vitest";
import { allowsUdpPort, parseInstances, parseSecurityGroups, safetyWarnings } from "@/lib/aws-status";

const instancesXml = `<DescribeInstancesResponse><reservationSet><item><instancesSet><item>
<instanceId>i-1</instanceId><instanceState><code>16</code><name>running</name></instanceState>
<instanceType>t3.micro</instanceType><ipAddress>1.2.3.4</ipAddress>
<groupSet><item><groupId>sg-1</groupId><groupName>wg</groupName></item></groupSet>
<tagSet><item><key>Name</key><value>wireguard-test</value></item></tagSet>
</item></instancesSet></item></reservationSet></DescribeInstancesResponse>`;

const sgXml = `<DescribeSecurityGroupsResponse><securityGroupInfo><item><groupId>sg-1</groupId><groupName>wg</groupName>
<ipPermissions>
<item><ipProtocol>udp</ipProtocol><fromPort>51820</fromPort><toPort>51820</toPort><ipRanges><item><cidrIp>0.0.0.0/0</cidrIp></item></ipRanges></item>
<item><ipProtocol>tcp</ipProtocol><fromPort>22</fromPort><toPort>22</toPort><ipRanges><item><cidrIp>0.0.0.0/0</cidrIp></item></ipRanges></item>
</ipPermissions></item></securityGroupInfo></DescribeSecurityGroupsResponse>`;

describe("aws status", () => {
  it("parses instance details", () => {
    expect(parseInstances(instancesXml)).toEqual([
      { id: "i-1", name: "wireguard-test", state: "running", type: "t3.micro", publicIp: "1.2.3.4", securityGroupIds: ["sg-1"] },
    ]);
  });
  it("detects the WireGuard UDP port 51820", () => {
    const [g] = parseSecurityGroups(sgXml);
    expect(g!.inbound.some((r) => allowsUdpPort(r, 51820))).toBe(true);
    expect(allowsUdpPort({ protocol: "tcp", from: 51820, to: 51820, cidrs: [] }, 51820)).toBe(false);
  });
  it("warns about SSH open to everyone but not the WireGuard port", () => {
    expect(safetyWarnings(parseSecurityGroups(sgXml)).map((w) => w.message)).toEqual(["wg: TCP 22 open to everyone"]);
  });
});
