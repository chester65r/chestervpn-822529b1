type Props = {
  down: number[];
  up: number[];
  active: boolean;
};

const W = 320;
const H = 120;

function buildPaths(values: number[], max: number) {
  const step = W / (values.length - 1);
  const pts = values.map((v, i) => [i * step, H - 6 - (v / max) * (H - 16)] as const);
  const line = pts.map(([x, y], i) => `${i === 0 ? "M" : "L"}${x.toFixed(1)},${y.toFixed(1)}`).join(" ");
  return { line, area: `${line} L${W},${H} L0,${H} Z` };
}

export function SpeedGraph({ down, up, active }: Props) {
  const max = Math.max(500_000, ...down, ...up) * 1.15;
  const d = buildPaths(down, max);
  const u = buildPaths(up, max);

  return (
    <svg
      viewBox={`0 0 ${W} ${H}`}
      className="h-32 w-full"
      role="img"
      aria-label="Live download and upload speed graph"
    >
      {[0.25, 0.5, 0.75].map((f) => (
        <line
          key={f}
          x1="0"
          x2={W}
          y1={H * f}
          y2={H * f}
          className="stroke-border"
          strokeDasharray="3 5"
        />
      ))}
      <path d={d.area} className={active ? "fill-primary/20" : "fill-muted-foreground/10"} />
      <path
        d={d.line}
        fill="none"
        strokeWidth="2"
        strokeLinejoin="round"
        className={active ? "stroke-primary" : "stroke-muted-foreground"}
      />
      <path d={u.area} className="fill-chart-4/10" />
      <path
        d={u.line}
        fill="none"
        strokeWidth="1.5"
        strokeLinejoin="round"
        className="stroke-chart-4"
        opacity={active ? 1 : 0.4}
      />
    </svg>
  );
}
