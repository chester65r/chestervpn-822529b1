import { AdditionalAccount } from "./AdditionalAccount";
import { ServiceOverview } from "./ServiceOverview";
import { ServersTab } from "./ServersTab";
import type { AdditionalSession } from "@/lib/use-additional-session";
import type { ComponentProps } from "react";

export function ServersWorkspace({
  session,
  onUseServer,
}: {
  session: AdditionalSession;
  onUseServer: ComponentProps<typeof ServersTab>["onUseServer"];
}) {
  return (
    <>
      <ServiceOverview />
      <AdditionalAccount session={session} />
      <h2 className="tc-section-title mb-3 mt-6">Cloud relay servers</h2>
      <ServersTab onUseServer={onUseServer} />
    </>
  );
}
