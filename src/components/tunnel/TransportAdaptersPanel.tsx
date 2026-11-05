import { useEffect, useMemo, useState } from "react";
import {
  DEFAULT_TRANSPORT_PROFILE,
  TRANSPORT_OPTIONS,
  TRANSPORT_PROFILE_STORAGE_KEY,
  getTransportOption,
  parseTransportProfile,
  serializeTransportProfile,
  validateTransportProfile,
  type TransportProfile,
} from "@/lib/transport-adapters";

const inputClass =
  "w-full rounded-xl border border-input bg-muted px-3 py-2.5 text-sm text-foreground outline-none focus:border-ring";

function ConfigField({
  label,
  value,
  error,
  onChange,
  placeholder,
}: {
  label: string;
  value: string;
  error?: string;
  onChange: (value: string) => void;
  placeholder?: string;
}) {
  return (
    <label className="block">
      <span className="mb-1 block text-xs font-medium text-muted-foreground">{label}</span>
      <input
        className={inputClass}
        value={value}
        placeholder={placeholder}
        onChange={(event) => onChange(event.target.value)}
        autoComplete="off"
        spellCheck={false}
      />
      {error && <span className="mt-1 block text-xs text-destructive">{error}</span>}
    </label>
  );
}

export function TransportAdaptersPanel() {
  const [profile, setProfile] = useState<TransportProfile>(DEFAULT_TRANSPORT_PROFILE);
  const [restored, setRestored] = useState(false);

  useEffect(() => {
    try {
      setProfile(
        parseTransportProfile(window.localStorage.getItem(TRANSPORT_PROFILE_STORAGE_KEY)),
      );
    } catch {
      setProfile(DEFAULT_TRANSPORT_PROFILE);
    } finally {
      setRestored(true);
    }
  }, []);

  useEffect(() => {
    if (!restored) return;
    try {
      window.localStorage.setItem(
        TRANSPORT_PROFILE_STORAGE_KEY,
        serializeTransportProfile(profile),
      );
    } catch {
      // Browser storage is optional; editing remains available in memory.
    }
  }, [profile, restored]);

  const option = getTransportOption(profile.protocol);
  const errors = useMemo(() => validateTransportProfile(profile), [profile]);
  const update = (patch: Partial<TransportProfile>) =>
    setProfile((current) => ({ ...current, ...patch }));

  return (
    <section className="space-y-3 rounded-2xl border border-border bg-card p-4">
      <div>
        <h3 className="text-sm font-semibold">Transport adapters</h3>
        <p className="mt-1 text-xs leading-relaxed text-muted-foreground">
          Choose a standard protocol for an endpoint you own or are authorized to use.
          WireGuard remains the only full-device VPN option in this client.
        </p>
      </div>

      <label className="block">
        <span className="mb-1 block text-xs font-medium text-muted-foreground">PROTOCOL</span>
        <select
          className={inputClass}
          value={profile.protocol}
          onChange={(event) => {
            const next = TRANSPORT_OPTIONS.find((item) => item.id === event.target.value);
            if (!next) return;
            update({
              protocol: next.id,
              tlsServerName: next.tls ? profile.endpointHost : "",
              websocketPath: profile.websocketPath || "/",
            });
          }}
        >
          {TRANSPORT_OPTIONS.map((item) => (
            <option key={item.id} value={item.id}>{item.label}</option>
          ))}
        </select>
      </label>

      <div className="rounded-xl bg-muted p-3">
        <p className="text-xs font-medium">{option.label}</p>
        <p className="mt-1 text-xs leading-relaxed text-muted-foreground">{option.description}</p>
        <p className="mt-2 text-[11px] font-medium text-muted-foreground">
          {option.fullDeviceVpn
            ? "Full-device VPN · existing WireGuard engine"
            : "Application-level adapter / endpoint test · not a full-device VPN"}
        </p>
      </div>

      {profile.protocol !== "wireguard" && (
        <>
          <div className="grid grid-cols-[1fr_6rem] gap-3">
            <ConfigField
              label="AUTHORIZED ENDPOINT HOST"
              value={profile.endpointHost}
              error={errors.endpointHost}
              placeholder="vpn.example.com"
              onChange={(value) =>
                update({
                  endpointHost: value,
                  tlsServerName: option.tls ? value : "",
                })
              }
            />
            <ConfigField
              label="PORT"
              value={profile.endpointPort}
              error={errors.endpointPort}
              placeholder="443"
              onChange={(value) => update({ endpointPort: value })}
            />
          </div>

          {option.tls && (
            <ConfigField
              label="TLS SERVER NAME"
              value={profile.tlsServerName}
              error={errors.tlsServerName}
              placeholder="Must match endpoint host"
              onChange={(value) => update({ tlsServerName: value })}
            />
          )}

          {(profile.protocol === "websocket" || profile.protocol === "websocket-tls") && (
            <ConfigField
              label="WEBSOCKET PATH"
              value={profile.websocketPath}
              error={errors.websocketPath}
              placeholder="/"
              onChange={(value) => update({ websocketPath: value })}
            />
          )}

          {option.kind === "proxy" && (
            <div className="grid grid-cols-[1fr_6rem] gap-3">
              <ConfigField
                label="DESTINATION HOST"
                value={profile.destinationHost}
                error={errors.destinationHost}
                placeholder="service.example.com"
                onChange={(value) => update({ destinationHost: value })}
              />
              <ConfigField
                label="DESTINATION PORT"
                value={profile.destinationPort}
                error={errors.destinationPort}
                placeholder="443"
                onChange={(value) => update({ destinationPort: value })}
              />
            </div>
          )}

          {option.tls ? (
            <p className="text-[11px] leading-relaxed text-muted-foreground">
              TLS adapters require normal certificate-chain validation and hostname verification.
              The TLS name is locked to the authorized endpoint identity; no trust-all mode or
              silent TLS downgrade is provided.
            </p>
          ) : (
            <p className="text-[11px] leading-relaxed text-muted-foreground">
              This protocol does not encrypt its own connection. Prefer a TLS-enabled variant
              when your authorized server supports it. Do not send credentials over plaintext.
            </p>
          )}

          {Object.keys(errors).length === 0 ? (
            <p className="text-[11px] text-muted-foreground">Profile fields pass local validation.</p>
          ) : (
            <p className="text-[11px] text-destructive">Complete the highlighted fields before testing.</p>
          )}
        </>
      )}

      {profile.protocol === "wireguard" && (
        <p className="text-xs leading-relaxed text-muted-foreground">
          Your existing WireGuard profile and server settings remain unchanged. Use the WireGuard
          section to import or manage the full-device VPN configuration.
        </p>
      )}

      <p className="border-t border-border pt-3 text-[11px] leading-relaxed text-muted-foreground">
        Saved locally in this browser only. This web panel validates and stores configuration;
        it does not open network connections, test the carrier, or start a device VPN. Proxy and
        stream adapters require a separate native integration and an authorized server endpoint.
      </p>
    </section>
  );
}
