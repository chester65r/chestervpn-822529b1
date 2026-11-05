# Android Xray engine — third-party notices

This native Android target can optionally bundle the upstream `libXray` Android AAR.
The fetched artifact is pinned to `XTLS/libXray v26.10.31` and SHA-256 verified before Gradle compilation:

```text
libxray-android.zip
SHA-256: fb99f04acf379cc2957c1e1d0ce2b3c0005d3f49974a44c09a11242a0562c1ca
Release: https://github.com/XTLS/libXray/releases/tag/v26.10.31
```

`libXray` is MIT-licensed. Its pinned Go module depends on `XTLS/Xray-core v26.10.31`, which is MPL-2.0 licensed. The exact upstream license texts are copied into the generated APK assets at `assets/licenses/`; the build does not modify either upstream project.

Sources:
- libXray source and MIT license: https://github.com/XTLS/libXray/tree/v26.10.31
- Xray-core source and MPL-2.0 license: https://github.com/XTLS/Xray-core/tree/v26.10.31
- Xray Android TUN descriptor integration: https://github.com/XTLS/Xray-core/blob/v26.10.31/proxy/tun/README.md

Do not redistribute the native APK without retaining these notices and making the corresponding upstream source and license texts available under their respective terms.
