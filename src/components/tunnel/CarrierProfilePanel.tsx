import { useEffect, useState } from "react";
import {
  CARRIER_LABELS,
  createCarrierProfile,
  type CarrierId,
} from "@/lib/carrier-profiles";

const STORAGE_KEY = "tunnel-console-carrier-profile";
const isCarrierId = (value: string | null): value is CarrierId =>
  value === "stc" || value === "mobily" || value === "manual";

export function CarrierProfilePanel() {
  const [carrierId, setCarrierId] = useState<CarrierId>("manual");

  useEffect(() => {
    try {
      const saved = window.localStorage.getItem(STORAGE_KEY);
      if (isCarrierId(saved)) setCarrierId(saved);
    } catch {
      // Storage is optional; manual selection remains available in memory.
    }
  }, []);

  useEffect(() => {
    try {
      window.localStorage.setItem(STORAGE_KEY, carrierId);
    } catch {
      // A blocked storage API must not interrupt configuration editing.
    }
  }, [carrierId]);

  const profile = createCarrierProfile(carrierId);

  return (
    <section className="space-y-3 rounded-2xl border border-border bg-card p-4">
      <div className="flex items-start justify-between gap-3">
        <div>
          <h3 className="text-sm font-semibold">Saudi carrier profile</h3>
          <p className="mt-1 text-xs text-muted-foreground">
            Select the SIM operator for compatibility checks. This does not change APN or carrier settings.
          </p>
        </div>
        <span className="rounded-full border border-border px-2 py-1 text-[10px] text-muted-foreground">
          UNVERIFIED
        </span>
      </div>

      <label className="block">
        <span className="mb-1.5 block text-xs font-medium tracking-wider text-muted-foreground">
          MOBILE OPERATOR
        </span>
        <select
          className="w-full rounded-xl border border-input bg-muted px-3.5 py-3 text-sm text-foreground"
          value={carrierId}
          onChange={(event) => setCarrierId(event.target.value as CarrierId)}
        >
          <option value="stc">{CARRIER_LABELS.stc}</option>
          <option value="mobily">{CARRIER_LABELS.mobily}</option>
          <option value="manual">{CARRIER_LABELS.manual}</option>
        </select>
      </label>

      <div className="grid grid-cols-2 gap-2 text-xs">
        <div className="rounded-xl bg-muted p-3">
          <p className="text-muted-foreground">APN</p>
          <p className="mt-1 font-medium">Not provided</p>
        </div>
        <div className="rounded-xl bg-muted p-3">
          <p className="text-muted-foreground">DNS / endpoint</p>
          <p className="mt-1 font-medium">Not provided</p>
        </div>
      </div>

      <p className="text-[11px] leading-relaxed text-muted-foreground">
        {profile.label} selected. No operator-specific values are assumed. Automatic detection requires
        the native Android companion and permission to read available SIM/network metadata.
      </p>
    </section>
  );
}
