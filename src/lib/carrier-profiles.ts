/**
 * Saudi carrier compatibility metadata.
 *
 * Values that would affect carrier provisioning (APN, DNS, endpoints and credentials)
 * intentionally start blank: the app must never guess operator-specific settings.
 */
export type CarrierId = "stc" | "mobily" | "manual";
export type CarrierVerification = "unverified" | "device-tested" | "operator-confirmed";

export type CarrierProfile = {
  id: CarrierId;
  label: string;
  operatorName: string;
  apn: string;
  dns: string[];
  endpointHost: string;
  endpointPort: string;
  tlsServerName: string;
  hostHeader: string;
  verification: CarrierVerification;
  notes: string;
};

export const CARRIER_LABELS: Record<CarrierId, string> = {
  stc: "STC Saudi Arabia",
  mobily: "Mobily Saudi Arabia",
  manual: "Other / manual",
};

export function createCarrierProfile(id: CarrierId): CarrierProfile {
  return {
    id,
    label: CARRIER_LABELS[id],
    operatorName: "",
    apn: "",
    dns: [],
    endpointHost: "",
    endpointPort: "443",
    tlsServerName: "",
    hostHeader: "",
    verification: "unverified",
    notes: "",
  };
}

/**
 * Best-effort text matching for Android operator names.
 * It deliberately does not infer MCC/MNC codes or roaming partner identity.
 */
export function detectCarrierFromOperatorName(operatorName: string | null | undefined): CarrierId {
  const name = (operatorName ?? "").normalize("NFKC").trim().toLocaleLowerCase("en");
  if (!name) return "manual";

  if (/\b(stc|saudi telecom|saudi telecommunications)\b/i.test(name)) return "stc";
  if (/\b(mobily|etihad etisalat)\b/i.test(name) || name.includes("موبايلي")) return "mobily";
  return "manual";
}

export function withOperatorDetection(
  operatorName: string | null | undefined,
): CarrierProfile {
  const id = detectCarrierFromOperatorName(operatorName);
  const profile = createCarrierProfile(id);
  return { ...profile, operatorName: (operatorName ?? "").trim() };
}

/** Never mark a profile verified merely because its fields pass validation. */
export function canMarkCarrierTested(evidence: {
  physicalDevice: boolean;
  activeSim: boolean;
  realConnectionTestPassed: boolean;
  endpointAuthorized: boolean;
}): boolean {
  return (
    evidence.physicalDevice &&
    evidence.activeSim &&
    evidence.realConnectionTestPassed &&
    evidence.endpointAuthorized
  );
}


/** Prefer the SIM/operator identity; a roaming network name is deliberately not accepted. */
export function detectCarrierFromSimIdentity(
  simOperatorName: string | null | undefined,
  carrierName: string | null | undefined,
): CarrierId {
  const simId = detectCarrierFromOperatorName(simOperatorName);
  return simId !== "manual" ? simId : detectCarrierFromOperatorName(carrierName);
}

/** Balance is intentionally not an input: Mobily auto-connect depends only on opt-in and SIM identity. */
export function shouldAutoConnectForMobilySim(input: {
  enabled: boolean;
  simOperatorName: string | null | undefined;
  carrierName: string | null | undefined;
}): boolean {
  return input.enabled && detectCarrierFromSimIdentity(input.simOperatorName, input.carrierName) === "mobily";
}
