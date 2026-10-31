import { ROUTE_PRESETS, parsePayload, visualizePayload, type ProfileErrors, type TunnelProfile } from "@/lib/tunnel";
import { DEFAULT_PAYLOAD_TEMPLATE } from "@/lib/tunnel";

type Props = {
  profile: TunnelProfile;
  presetId: string;
  errors: ProfileErrors;
  disabled: boolean;
  onPreset: (id: string) => void;
  onChange: (patch: Partial<TunnelProfile>) => void;
};

const fieldClass =
  "w-full rounded-xl border border-input bg-muted px-3.5 py-3 text-sm text-foreground outline-none transition-colors placeholder:text-muted-foreground/60 focus:border-ring disabled:opacity-50";

function Field({ label, error, children }: { label: string; error?: string | undefined; children: React.ReactNode }) {
  return (
    <label className="block">
      <span className="mb-1.5 block text-xs font-medium tracking-wider text-muted-foreground">{label}</span>
      {children}
      {error && <span className="mt-1 block text-xs text-destructive">{error}</span>}
    </label>
  );
}

export function ConfigTab({ profile, presetId, errors, disabled, onPreset, onChange }: Props) {
  const preview = visualizePayload(parsePayload(profile.payload, profile.sni || "[host]"));

  return (
    <div className="space-y-4">
      {disabled && (
        <p className="rounded-xl border border-border bg-muted px-3 py-2 text-xs text-muted-foreground">
          Disconnect to edit the configuration.
        </p>
      )}
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
            placeholder="203.0.113.10"
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
          placeholder="server.example.com"
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
        <p className="mb-1.5 text-xs font-medium tracking-wider text-muted-foreground">LIVE PARSED PREVIEW</p>
        <pre className="overflow-x-auto whitespace-pre-wrap break-all rounded-xl border border-border bg-background p-3 font-mono text-xs leading-relaxed text-primary">
          {preview}
        </pre>
      </div>
    </div>
  );
}
