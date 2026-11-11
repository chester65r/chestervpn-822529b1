import {
  DEFAULT_PAYLOAD_TEMPLATE,
  HETZNER_SERVER_SPEC,
  NETWORK_PROVIDER_PROFILES,
  ROUTE_PRESETS,
  parsePayload,
  visualizePayload,
  type NetworkProviderProfile,
  type ProfileErrors,
  type TunnelProfile,
} from "@/lib/tunnel";

type Props = {
  profile: TunnelProfile;
  presetId: string;
  networkProfileId: string;
  errors: ProfileErrors;
  disabled: boolean;
  onPreset: (id: string) => void;
  onSelectNetworkProfile: (profile: NetworkProviderProfile) => void;
  onChange: (patch: Partial<TunnelProfile>) => void;
};

const fieldClass = "tc-field py-3";

function Field({ label, error, children }: { label: string; error?: string | undefined; children: React.ReactNode }) {
  return (
    <label className="block">
      <span className="tc-label">{label}</span>
      {children}
      {error && <span className="mt-1 block text-xs text-destructive">{error}</span>}
    </label>
  );
}

export function ConfigTab({
  profile,
  presetId,
  networkProfileId,
  errors,
  disabled,
  onPreset,
  onSelectNetworkProfile,
  onChange,
}: Props) {
  const preview = visualizePayload(parsePayload(profile.payload, profile.sni || "[host]"));
  const activeNetProfile =
    NETWORK_PROVIDER_PROFILES.find((item) => item.id === networkProfileId) ??
    NETWORK_PROVIDER_PROFILES[0];

  return (
    <div className="space-y-5">
      {disabled && (
        <p className="rounded-xl border border-chart-4/30 bg-chart-4/10 px-3.5 py-2.5 text-xs text-foreground">
          Disconnect to edit the configuration.
        </p>
      )}

      <div className="tc-card-accent space-y-3 p-4">
        <div className="flex items-center justify-between gap-2">
          <span className="text-xs font-semibold tracking-wider text-primary">
            HETZNER CX23 ({HETZNER_SERVER_SPEC.ip}) NETWORK & TELECOM PROFILE
          </span>
          <span className="rounded bg-secondary px-2 py-0.5 font-mono text-[10px] text-primary">
            {HETZNER_SERVER_SPEC.serverType} · {HETZNER_SERVER_SPEC.vcpu} vCPU / {HETZNER_SERVER_SPEC.ramGb} GB
          </span>
        </div>
        <select
          className={fieldClass}
          value={networkProfileId}
          disabled={disabled}
          onChange={(e) => {
            const found = NETWORK_PROVIDER_PROFILES.find((item) => item.id === e.target.value);
            if (found) onSelectNetworkProfile(found);
          }}
        >
          {NETWORK_PROVIDER_PROFILES.map((item) => (
            <option key={item.id} value={item.id}>
              {item.name} — {item.networkType}
            </option>
          ))}
        </select>
        {activeNetProfile && (
          <div className="tc-code px-3 py-2.5 font-sans text-xs text-muted-foreground">
            <p className="text-foreground">{activeNetProfile.description}</p>
            <p className="mt-1 font-mono text-[11px] text-primary">
              Endpoint: {activeNetProfile.profile.ip}:{activeNetProfile.profile.port} · Recommended MTU:{" "}
              {activeNetProfile.recommendedMtu} · Keepalive: {activeNetProfile.keepAliveIntervalSec}s
            </p>
          </div>
        )}
      </div>

      <Field label="ROUTE PRESET">
        <select
          className={fieldClass}
          value={presetId}
          disabled={disabled}
          onChange={(e) => onPreset(e.target.value)}
        >
          {ROUTE_PRESETS.map((p) => (
            <option key={p.id} value={p.id}>
              {p.label}
            </option>
          ))}
        </select>
      </Field>

      <div className="grid grid-cols-[1fr_6.5rem] gap-3">
        <Field label="SERVER IP / DOMAIN" error={errors.ip}>
          <input
            className={fieldClass}
            placeholder="2.29.8.194"
            value={profile.ip}
            disabled={disabled}
            onChange={(e) => onChange({ ip: e.target.value })}
          />
        </Field>
        <Field label="PORT" error={errors.port}>
          <input
            className={fieldClass}
            inputMode="numeric"
            placeholder="443"
            value={profile.port}
            disabled={disabled}
            onChange={(e) => onChange({ port: e.target.value })}
          />
        </Field>
      </div>

      <Field label="SERVER NAME (SNI)" error={errors.sni}>
        <input
          className={fieldClass}
          placeholder="2.29.8.194"
          value={profile.sni}
          disabled={disabled}
          onChange={(e) => onChange({ sni: e.target.value })}
        />
      </Field>

      <Field label="HANDSHAKE PAYLOAD">
        <textarea
          className={`${fieldClass} min-h-24 font-mono text-xs`}
          placeholder={DEFAULT_PAYLOAD_TEMPLATE}
          value={profile.payload}
          disabled={disabled}
          onChange={(e) => onChange({ payload: e.target.value })}
        />
        <span className="mt-1 block text-xs text-muted-foreground">
          Macros: <code className="font-mono text-primary">[host]</code>{" "}
          <code className="font-mono text-primary">[crlf]</code>. Leave blank for the default template.
        </span>
      </Field>

      <div>
        <p className="tc-label">LIVE PARSED PREVIEW</p>
        <pre className="tc-code overflow-x-auto whitespace-pre-wrap break-all p-3 text-xs leading-relaxed text-primary">
          {preview}
        </pre>
      </div>
    </div>
  );
}
