# Logo

`logo-source.png` is the artwork supplied for the app (black mark on a baked-in checkerboard). It was
traced to a vector (`logo-white.svg`, `logo-black.svg`) with potrace; the same path is in
`shared/src/commonMain/kotlin/com/auroratv/ui/components/Logo.kt` for in-app use. `icon.svg` is the
launcher icon (logo on the app's dark gradient); the Android mipmaps, TV banner and the Windows
`.ico`/window icon are rendered from these files.

# Brand tile artwork

`shared/src/commonMain/composeResources/drawable/brand_appletv.png` and `brand_prime.png` are made
from the Apple TV and Prime Video logos supplied by the project owner (`appletv-source.png`,
`primevideo-source.png`), cropped to the 16:9 tile. Those logos are trademarks of Apple Inc. and
Amazon.com, Inc.; they are not covered by this project's GPL licence. Other brand tiles use the
studio image from the user's own Jellyfin server, or the brand's name drawn in the app's type.
