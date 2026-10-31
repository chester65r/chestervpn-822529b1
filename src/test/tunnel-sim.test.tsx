import { StrictMode, type ReactNode } from "react";
import { act, cleanup, renderHook } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { useTunnelSim } from "@/lib/use-tunnel-sim";

afterEach(() => { cleanup(); vi.useRealTimers(); vi.restoreAllMocks(); });

describe("tunnel simulation lifecycle", () => {
  it("counts download and upload bytes consistently under StrictMode", () => {
    vi.useFakeTimers();
    vi.spyOn(Math, "random").mockReturnValue(0.5);
    const wrapper = ({ children }: { children: ReactNode }) => <StrictMode>{children}</StrictMode>;
    const { result } = renderHook(useTunnelSim, { wrapper });
    act(() => result.current.connect());
    act(() => vi.advanceTimersByTime(1500));
    act(() => vi.advanceTimersByTime(500));
    expect(result.current.downSpeed).toBe(210000);
    expect(result.current.upSpeed).toBe(42000);
    expect(result.current.totals).toEqual({ rx: 105000, tx: 21000 });
    act(() => vi.advanceTimersByTime(500));
    expect(result.current.totals).toEqual({ rx: 278250, tx: 55650 });
    act(() => result.current.disconnect());
    expect(result.current.totals).toEqual({ rx: 0, tx: 0 });
  });

  it("cancels a pending connection when disconnected", () => {
    vi.useFakeTimers();
    const { result } = renderHook(useTunnelSim);
    act(() => result.current.connect());
    act(() => result.current.disconnect());
    act(() => vi.advanceTimersByTime(2000));
    expect(result.current.status).toBe("disconnected");
    expect(result.current.seconds).toBe(0);
  });
});