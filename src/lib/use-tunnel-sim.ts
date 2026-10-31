import { useEffect, useRef, useState } from "react";

export type TunnelStatus = "disconnected" | "connecting" | "connected";

const POINTS = 40;
const flat = () => Array.from({ length: POINTS }, () => 0);

/** Purely visual simulation: a browser cannot open a real VPN tunnel. */
export function useTunnelSim() {
  const [status, setStatus] = useState<TunnelStatus>("disconnected");
  const [down, setDown] = useState<number[]>(flat);
  const [up, setUp] = useState<number[]>(flat);
  const [seconds, setSeconds] = useState(0);
  const [totals, setTotals] = useState({ rx: 0, tx: 0 });
  const target = useRef({ down: 600_000, up: 120_000 });
  const currentSpeed = useRef({ down: 0, up: 0 });

  const connect = () => setStatus((s) => s === "disconnected" ? "connecting" : s);

  useEffect(() => {
    if (status !== "connecting") return;
    const id = window.setTimeout(() => setStatus("connected"), 1500);
    return () => window.clearTimeout(id);
  }, [status]);

  const disconnect = () => {
    setStatus("disconnected");
    setDown(flat());
    setUp(flat());
    setSeconds(0);
    setTotals({ rx: 0, tx: 0 });
    currentSpeed.current = { down: 0, up: 0 };
  };

  useEffect(() => {
    if (status !== "connected") return;
    const id = window.setInterval(() => {
      if (Math.random() < 0.15) {
        target.current = {
          down: 150_000 + Math.random() * 2_400_000,
          up: 30_000 + Math.random() * 500_000,
        };
      }
      const step = (last: number, goal: number) =>
        Math.max(0, last + (goal - last) * 0.35 + (Math.random() - 0.5) * goal * 0.25);
      const d = step(currentSpeed.current.down, target.current.down);
      const u = step(currentSpeed.current.up, target.current.up);
      currentSpeed.current = { down: d, up: u };
      setDown((prev) => [...prev.slice(1), d]);
      setUp((prev) => [...prev.slice(1), u]);
      setTotals((t) => ({ rx: t.rx + d * 0.5, tx: t.tx + u * 0.5 }));
    }, 500);
    return () => window.clearInterval(id);
  }, [status]);

  useEffect(() => {
    if (status !== "connected") return;
    const id = window.setInterval(() => setSeconds((s) => s + 1), 1000);
    return () => window.clearInterval(id);
  }, [status]);

  return {
    status,
    down,
    up,
    seconds,
    totals,
    connect,
    disconnect,
    downSpeed: down[down.length - 1] ?? 0,
    upSpeed: up[up.length - 1] ?? 0,
  };
}
