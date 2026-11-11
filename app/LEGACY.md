# LEGACY — superseded AI Studio export

This `app/` tree is the original **Google AI Studio export** of the Android app.
It is **superseded and is NOT the product**:

- It is not built, tested, released, or shipped. Do not treat anything in this
  tree as product evidence or as the current Android implementation.
- The product Android app is the blueprint app **generated from
  `native/android`** (see `native/android/generate-project.ts` and the Android
  sections of the project docs), built on the official WireGuard Android tunnel
  library and the shared profile generator in `src/lib/wireguard.ts`.
- This tree is retained **for reference only**, so earlier design and code can
  be consulted while working on the real app.

If you are looking for the Android product, start at `native/android/` — not here.
