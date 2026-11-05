import { describe, expect, it } from "vitest";
import {
  canMarkCarrierTested,
  createCarrierProfile,
  detectCarrierFromOperatorName,
  withOperatorDetection,
} from "@/lib/carrier-profiles";

describe("Saudi carrier profile metadata", () => {
  it("creates separate empty STC and Mobily profiles without invented network values", () => {
    const stc = createCarrierProfile("stc");
    const mobily = createCarrierProfile("mobily");

    expect(stc.id).toBe("stc");
    expect(mobily.id).toBe("mobily");
    for (const profile of [stc, mobily]) {
      expect(profile.apn).toBe("");
      expect(profile.dns).toEqual([]);
      expect(profile.endpointHost).toBe("");
      expect(profile.tlsServerName).toBe("");
      expect(profile.hostHeader).toBe("");
      expect(profile.verification).toBe("unverified");
    }
  });

  it("detects only recognizable operator names", () => {
    expect(detectCarrierFromOperatorName("stc")).toBe("stc");
    expect(detectCarrierFromOperatorName("Saudi Telecom Company")).toBe("stc");
    expect(detectCarrierFromOperatorName("Mobily")).toBe("mobily");
    expect(detectCarrierFromOperatorName("Etihad Etisalat")).toBe("mobily");
    expect(detectCarrierFromOperatorName("")).toBe("manual");
    expect(detectCarrierFromOperatorName(null)).toBe("manual");
    expect(detectCarrierFromOperatorName("Unknown roaming partner")).toBe("manual");
  });

  it("preserves the observed operator label but starts unverified", () => {
    const profile = withOperatorDetection("Mobily");
    expect(profile.id).toBe("mobily");
    expect(profile.operatorName).toBe("Mobily");
    expect(profile.verification).toBe("unverified");
  });

  it("requires physical SIM and real authorized endpoint evidence before marking tested", () => {
    expect(
      canMarkCarrierTested({
        physicalDevice: true,
        activeSim: true,
        realConnectionTestPassed: true,
        endpointAuthorized: true,
      }),
    ).toBe(true);

    expect(
      canMarkCarrierTested({
        physicalDevice: false,
        activeSim: true,
        realConnectionTestPassed: true,
        endpointAuthorized: true,
      }),
    ).toBe(false);
  });
});
