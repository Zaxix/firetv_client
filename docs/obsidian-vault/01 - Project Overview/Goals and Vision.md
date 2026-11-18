---
tags: [overview]
---
# Goals and Vision

Sharkfin aims to feel like a **premium smart-TV streaming app** on top of a home Jellyfin server.
The working principles, taken from the requests that shaped each update (see [[Change Log]]):

1. **Experience over features.** Polish what exists (focus, scrolling, loading, playback start)
   before adding more.
2. **Performance and quality first** — on the TV *and* on the server. The server is modest hardware,
   so optimisations are client-side where possible; nothing should make the server transcode, scan
   or decode more than the viewer asked for (see [[Hardware Limitations]], [[Optimization Notes]]).
3. **Remote-control navigation must be predictable.** Focus never jumps to unrelated regions, never
   traps the viewer, and always returns to where it was ([[Navigation System]], [[Focus Management]]).
4. **Preserve the design.** The look (frosted glass, the details-page background, the tvOS-like
   sidebar) is considered settled; fixes must keep it ([[Design System]]).
5. **Honesty in reporting.** Nothing is called fixed or tested unless it was measured or checked in
   CI; hardware-only behaviour is labelled as such ([[Testing Guide]], [[Known Issues]]).
6. **Security.** Tokens only in the platform vault, never in URLs the app builds or in logs
   ([[Data Storage]]).

## Related notes
[[App Overview]] · [[Future Improvements]]
