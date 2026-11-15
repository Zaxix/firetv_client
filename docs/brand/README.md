# Logo

`logo-source.png` is the artwork supplied for the app (black mark on a baked-in checkerboard). It was
traced to a vector (`logo-white.svg`, `logo-black.svg`) with potrace; the same path is in
`shared/src/commonMain/kotlin/com/auroratv/ui/components/Logo.kt` for in-app use. `icon.svg` is the
launcher icon (logo on the app's dark gradient); the Android mipmaps, TV banner and the Windows
`.ico`/window icon are rendered from these files.

# Brand tile artwork

The brand tile logos in `shared/src/commonMain/composeResources/drawable/` come from logos supplied
by the project owner (sources here: `appletv-source.png`, `netflix-source.png`, `primevideo-source.png`,
`disneyplus-source.png`, `paramountplus-source.png`). `brand_appletv.png` is the Apple TV logo on a
16:9 dark tile; `brand_netflix.png`, `brand_prime_logo.png`, `brand_disney.png` and
`brand_paramount.png` are the logos trimmed onto transparency, with the dark lettering of the Prime
Video, Disney+ and Paramount+ logos turned white so they read on the app's dark tiles (the Netflix
logo and the Disney+ arc keep their colours; Prime Video is upscaled from a 150 px source). They are
drawn over each brand's own gradient. These logos are trademarks of Apple Inc., Netflix, Inc.,
Amazon.com, Inc., Disney Enterprises, Inc. and Paramount Global; they are not covered by this
project's GPL licence. Other brand tiles use the studio image from the user's own Jellyfin server,
or the brand's name drawn in the app's type.
