# Codec, HDR, audio and subtitle capability matrix

Nameless never assumes support. On Android every row marked *probed* is measured at launch from
`MediaCodecList`, the display's HDR capabilities and the HDMI sink's audio capabilities, and the
result is sent to Jellyfin, which transcodes anything the device can't handle. **Settings → About**
shows exactly what was detected on your device.

Legend: ✅ supported · 🔎 probed per device (hardware dependent) · 🔁 server transcodes/converts · ➖ not applicable

## Video

| Format | Fire TV / Android TV (Media3) | Windows (libmpv) |
|---|---|---|
| H.264 / AVC 8-bit | 🔎 hardware MediaCodec (all Fire TV devices) | ✅ D3D11VA/NVDEC, software fallback |
| H.264 Hi10P (10-bit) | 🔁 (not supported by TV hardware decoders) | ✅ software |
| H.265 / HEVC Main | 🔎 hardware where present (Fire TV Stick 2nd gen+ / 4K / Cube) | ✅ hardware where GPU supports |
| H.265 / HEVC Main10 | 🔎 only if the decoder reports `HEVCProfileMain10`; else 🔁 | ✅ |
| VP9 (Profile 0 / 2) | 🔎 | ✅ |
| AV1 (Main / Main10) | 🔎 (e.g. Fire TV Stick 4K Max); else 🔁 | ✅ hardware on GPUs with AV1 decode, otherwise software (dav1d) |
| MPEG-2 | 🔎 | ✅ |
| MPEG-4 Part 2 / VC-1 | 🔎 (often absent → 🔁) | ✅ |
| 4K (2160p) | 🔎 decoder must report 3840×2160 | ✅ |
| Software decoders on Android | capped at 1080p in the profile (TV SoCs can't software-decode 4K in real time) | ➖ |

## HDR

| Format | Fire TV / Android TV | Windows |
|---|---|---|
| HDR10 | 🔎 display must report HDR10 + 10-bit decoder; otherwise server tone-maps (🔁) | ✅ passes through with Windows HDR on (`target-colorspace-hint`), else mpv tone-maps to SDR |
| HDR10+ | 🔎 (plays as HDR10 where HDR10+ isn't reported) | ✅ as HDR10 |
| HLG | 🔎 | ✅ |
| HDR10+ inside Dolby Vision | 🔎 on Fire TV 4K / 4K Max (AFTMM, AFTKA, AFTKM, AFTKRT) the HDR10+ SEI messages are masked on the way into the DV decoder, which mishandles streams carrying both (media3 #11096) | ✅ |
| Dolby Vision 8.1 / 8.4 / 8.2 (WEB-DL, streaming rips) | 🔎 DV decoder declaring profile 8 + DV display → real Dolby Vision; otherwise the track is handed to the plain HEVC decoder and plays its HDR10 / HLG / SDR base layer | ✅ libplacebo applies the RPU (gpu-next) |
| Dolby Vision 7 FEL/MEL (UHD Blu-ray remuxes) | 🔎 decoder declaring profile 7 (or a Shield, which decodes it without declaring it) → native; DV decoder with only profile 8 (Fire TV, most TVs) → **converted to 8.1 on the fly** (libdovi rewrites the RPU, the enhancement layer is dropped, nothing is re-encoded) so the TV still switches to Dolby Vision; no DV display → HDR10 base layer | ✅ base layer + RPU via libplacebo (the enhancement layer is ignored) |
| Dolby Vision 5 (no base layer) | 🔎 DV decoder declaring profile 5 + DV display → native; otherwise 🔁 server converts (it can't be shown correctly any other way) | ✅ libplacebo reshaping |
| Dolby Vision 10 (AV1) | 🔎 AV1 DV decoder → native; 10.1/10.2/10.4 otherwise play their AV1 base layer; 10.0 → 🔁 | ✅ |
| Invalid DV signalling (`DOVIInvalid`) | 🔁 always left to the server | 🔁 |

Which path a file took is shown in the player's info panel (*HDR path*), and *Settings → About → Dolby
Vision* shows what the decoder and display declared. The Android mpv engine has no Dolby Vision output
path, so with mpv selected DV files play their base layer and profile 5 is converted by the server; use
ExoPlayer (the default) for Dolby Vision on a TV.

## Audio

| Format | Fire TV / Android TV | Windows |
|---|---|---|
| AAC, MP3 | ✅ | ✅ |
| FLAC, Opus, Vorbis, ALAC | ✅ (platform or FFmpeg extension) | ✅ |
| AC3 (Dolby Digital) | 🔎 passthrough if the HDMI sink accepts it, else decode (platform or FFmpeg) | ✅ decode; passthrough optional (Settings) |
| E-AC3 (DD+), incl. Atmos JOC | 🔎 passthrough / decode | ✅ decode; passthrough optional |
| DTS / DTS-HD | 🔎 passthrough; else FFmpeg core decode | ✅ decode; passthrough optional |
| TrueHD (incl. Atmos) | 🔎 passthrough; else FFmpeg decode | ✅ decode; passthrough optional |
| Multichannel (5.1 / 7.1) | 🔎 channel count from the audio sink | ✅ up to 8 channels |
| Audio track selection | ✅ instant (Direct Play), renegotiated when transcoding | ✅ |

Passthrough on Windows can't be auto-detected reliably (S/PDIF/HDMI receivers aren't queryable), so
it's an explicit setting and off by default.

## Subtitles

| Format | Fire TV / Android TV | Windows |
|---|---|---|
| ASS / SSA (embedded) | ✅ **libass** (ass-media `OVERLAY_OPEN_GL`): fonts, sizes, colours, `\pos`/`\move`, alignment, outlines/shadows, `\k` karaoke, `\t` animation, `\clip`, rotation, layering | ✅ libass in mpv |
| ASS / SSA (external file) | ✅ libass | ✅ libass |
| Embedded MKV fonts | ✅ (ass-media reads Matroska attachments) | ✅ (`embeddedfonts=yes`) |
| ASS while the server transcodes | ✅ delivered as the original `.ass` file (External), never flattened to SRT | ✅ |
| SRT / SubRip | ✅ | ✅ |
| WebVTT | ✅ | ✅ |
| PGS (Blu-ray) | ✅ embedded (Media3 PGS decoder) | ✅ |
| VobSub (DVD) | ✅ embedded (Media3 VobSub decoder) | ✅ |
| DVB subtitles | ✅ embedded | ✅ |
| Anything else / devices without a renderer | 🔁 burned in by the server ("Encode"), never silently dropped | ➖ |
| Subtitle track selection, on/off | ✅ | ✅ |
| Subtitle delay | ❌ not supported by Media3's text pipeline — the control is hidden (see Known limitations) | ✅ ±30 s in 100 ms steps |
| Subtitle size | ✅ SRT/VTT/PGS; ASS keeps the author's sizes | ✅ (`sub-scale` with `sub-ass-override=scale`) |
| Language / default / forced preferences | ✅ Default, Always, Forced only, Smart (subtitles only when the audio isn't in your language), Off | ✅ |

## Containers (Direct Play)

* **Android:** MP4/M4V/MOV, MKV/WebM, MPEG-TS/M2TS, AVI, Ogg. Anything else gets remuxed by the server (Direct Stream).
* **Windows:** everything FFmpeg demuxes (MKV, MP4, AVI, TS, M2TS, WMV/ASF, FLV, VOB, …).
