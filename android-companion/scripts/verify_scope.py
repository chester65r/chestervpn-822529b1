#!/usr/bin/env python3
"""Fail the build if the local-only companion gains Internet or VPN capabilities."""

from pathlib import Path
import sys
import xml.etree.ElementTree as ET

ANDROID_NS = "http://schemas.android.com/apk/res/android"
ANDROID_NAME = f"{{{ANDROID_NS}}}name"
ANDROID_PERMISSION = f"{{{ANDROID_NS}}}permission"
ANDROID_USES_CLEARTEXT = f"{{{ANDROID_NS}}}usesCleartextTraffic"
ANDROID_ALLOW_BACKUP = f"{{{ANDROID_NS}}}allowBackup"

ALLOWED_PERMISSIONS = {"android.permission.ACCESS_NETWORK_STATE"}
VPN_SERVICE_ACTION = "android.net.VpnService"
VPN_BIND_PERMISSION = "android.permission.BIND_VPN_SERVICE"


def main() -> int:
    manifest = (
        Path(__file__).resolve().parents[1]
        / "app"
        / "src"
        / "main"
        / "AndroidManifest.xml"
    )

    try:
        root = ET.parse(manifest).getroot()
    except (OSError, ET.ParseError) as error:
        print(f"Scope check failed: cannot read {manifest}: {error}", file=sys.stderr)
        return 1

    permission_tags = {
        "uses-permission",
        "uses-permission-sdk-23",
        "uses-permission-sdk-m",
    }
    declared_permissions = {
        element.attrib[ANDROID_NAME]
        for element in root.iter()
        if element.tag in permission_tags and ANDROID_NAME in element.attrib
    }
    if declared_permissions != ALLOWED_PERMISSIONS:
        unexpected = sorted(declared_permissions - ALLOWED_PERMISSIONS)
        missing = sorted(ALLOWED_PERMISSIONS - declared_permissions)
        print(
            "Scope check failed: manifest permissions differ from the approved "
            f"local-only set. Unexpected={unexpected}; missing={missing}",
            file=sys.stderr,
        )
        return 1

    application = root.find("application")
    if application is None:
        print("Scope check failed: application element is missing.", file=sys.stderr)
        return 1

    if application.attrib.get(ANDROID_USES_CLEARTEXT) != "false":
        print("Scope check failed: cleartext traffic must remain disabled.", file=sys.stderr)
        return 1

    if application.attrib.get(ANDROID_ALLOW_BACKUP) != "false":
        print("Scope check failed: Android backup must remain disabled.", file=sys.stderr)
        return 1

    for service in application.findall("service"):
        if service.attrib.get(ANDROID_PERMISSION) == VPN_BIND_PERMISSION:
            print("Scope check failed: a system VPN service was declared.", file=sys.stderr)
            return 1

        for action in service.findall("./intent-filter/action"):
            if action.attrib.get(ANDROID_NAME) == VPN_SERVICE_ACTION:
                print("Scope check failed: a system VPN service action was declared.", file=sys.stderr)
                return 1

    print(
        "Companion scope verified: ACCESS_NETWORK_STATE only; "
        "no Internet permission or VPN service; cleartext and backup disabled."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
