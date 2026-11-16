import { useRouterState } from "@tanstack/react-router";
import { SpeedInsights } from "@vercel/speed-insights/react";

export function AppSpeedInsights() {
  // Use the matched template, never the URL's query, hash, or concrete account IDs.
  // Subscribing also updates attribution after client-side navigation.
  const route = useRouterState({
    select: (state) => state.matches.at(-1)?.fullPath ?? "/",
  });

  return <SpeedInsights route={route} />;
}
