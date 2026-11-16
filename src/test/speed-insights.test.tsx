import { cleanup, render } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { AppSpeedInsights } from "@/components/AppSpeedInsights";

const router = vi.hoisted(() => ({
  state: {
    matches: [{ fullPath: "/" }],
    location: { pathname: "/", searchStr: "", hash: "" },
  },
}));

vi.mock("@tanstack/react-router", () => ({
  useRouterState: ({ select }: { select: (state: typeof router.state) => string }) =>
    select(router.state),
}));

const scripts = () =>
  document.head.querySelectorAll<HTMLScriptElement>(
    'script[src="/_vercel/speed-insights/script.js"]',
  );

beforeEach(() => {
  vi.stubEnv("NODE_ENV", "production");
  router.state = {
    matches: [{ fullPath: "/" }],
    location: { pathname: "/", searchStr: "", hash: "" },
  };
});

afterEach(() => {
  cleanup();
  scripts().forEach((script) => script.remove());
  delete window.si;
  delete window.siq;
  vi.unstubAllEnvs();
});

describe("Speed Insights TanStack integration", () => {
  it("injects the real SDK script once and updates its route on client navigation", () => {
    const { rerender } = render(<AppSpeedInsights />);
    expect(scripts()).toHaveLength(1);
    expect(scripts()[0]?.dataset["route"]).toBe("/");
    expect(scripts()[0]?.dataset["sdkn"]).toBe("@vercel/speed-insights/react");

    router.state.matches = [{ fullPath: "/" }, { fullPath: "/connect" }];
    rerender(<AppSpeedInsights />);

    expect(scripts()).toHaveLength(1);
    expect(scripts()[0]?.dataset["route"]).toBe("/connect");
  });

  it("attributes dynamic pages to route templates instead of account IDs or URL parameters", () => {
    router.state = {
      matches: [{ fullPath: "/" }, { fullPath: "/accounts/$accountId" }],
      location: {
        pathname: "/accounts/private-account",
        searchStr: "?code=private-code",
        hash: "private-hash",
      },
    };
    render(<AppSpeedInsights />);

    expect(scripts()[0]?.dataset["route"]).toBe("/accounts/$accountId");
  });
});
