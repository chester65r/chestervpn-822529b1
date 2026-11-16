import { createElement, type ComponentType } from "react";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

const loaded = vi.hoisted(() => ({ android: vi.fn(), servers: vi.fn() }));
vi.mock("use-webmcp-tool", () => ({ useWebMCP: vi.fn() }));
vi.mock("@tanstack/react-router", async (original) => ({
  ...(await original<typeof import("@tanstack/react-router")>()),
  Link: ({ children }: { children: React.ReactNode }) => <span>{children}</span>,
}));
vi.mock("@/lib/use-additional-session", () => ({
  useAdditionalSession: () => ({ previewSession: true }),
}));
vi.mock("@/components/tunnel/HomeTab", () => ({ HomeTab: () => <h2>Home content</h2> }));
vi.mock("@/components/tunnel/ConfigTab", () => ({ ConfigTab: () => <h2>Configure content</h2> }));
vi.mock("@/components/tunnel/ShareTab", () => ({ ShareTab: () => <h2>Share content</h2> }));
vi.mock("@/components/tunnel/BlueprintTab", () => {
  loaded.android();
  return { BlueprintTab: () => <h2>Android content</h2> };
});
vi.mock("@/components/tunnel/ServersWorkspace", () => {
  loaded.servers();
  return { ServersWorkspace: () => <h2>Servers content</h2> };
});

import { Route } from "@/routes/index";

afterEach(cleanup);

describe("deferred workspace tabs", () => {
  it("renders Home immediately without loading unopened workspaces, then switches through each tab", async () => {
    render(createElement(Route.options.component as ComponentType));
    expect(screen.getByText("Home content")).toBeInTheDocument();
    expect(screen.queryByText("Loading workspace…")).not.toBeInTheDocument();
    expect(loaded.android).not.toHaveBeenCalled();
    expect(loaded.servers).not.toHaveBeenCalled();

    for (const [tab, content] of [
      ["Configure", "Configure content"],
      ["Share", "Share content"],
      ["Android", "Android content"],
      ["Servers", "Servers content"],
      ["Home", "Home content"],
    ] as const) {
      fireEvent.click(screen.getByRole("button", { name: tab }));
      expect(await screen.findByText(content)).toBeInTheDocument();
      expect(screen.getByRole("button", { name: tab })).toHaveAttribute("aria-current", "page");
    }
    expect(loaded.android).toHaveBeenCalledOnce();
    expect(loaded.servers).toHaveBeenCalledOnce();
  });
});
