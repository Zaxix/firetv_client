# Logo

`logo-source.png` is the artwork supplied for the app (black mark on a baked-in checkerboard). It was
traced to a vector (`logo-white.svg`, `logo-black.svg`) with potrace; the same path is in
`shared/src/commonMain/kotlin/com/auroratv/ui/components/Logo.kt` for in-app use. `icon.svg` is the
launcher icon (logo on the app's dark gradient); the Android mipmaps, TV banner and the Windows
`.ico`/window icon are rendered from these files.
