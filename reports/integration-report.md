| | Check | Result |
|---|---|---|
| ✅ | Server discovery from bare host | found Jellyfin 12.4.0 at http://localhost:8096 |
| ✅ | Invalid credentials rejected | InvalidCredentials |
| ✅ | Authentication + secure token storage | signed in as aurora; token only in vault |
| ✅ | Session restore after relaunch | restored |
| ✅ | Expired/invalid token detected | SessionExpired |
| ✅ | Libraries | Movies(movies), TV Shows(tvshows) |
| ✅ | Library pagination | 11 movies over 6 pages of 2, no duplicates |
| ✅ | Search | 'meridian' → Glass Meridian |
| ✅ | Metadata + artwork from server | overview, 2 genres, poster image/jpeg, blurhash=true |
| ✅ | Brand tiles: studios from metadata resolve to brands, and a hub lists their titles | brands=[netflix, disney, appletv, paramount, hbo, hulu, natgeo, a24, crunchyroll]; Netflix hub → [Glass Meridian] |
| ✅ | Critic score from server metadata feeds the ratings strip | CriticRating=93.0 → tomatometer 93%; providerIds=[] |
| ✅ | Rating keys typed on one device reach another (Jellyfin DisplayPreferences) | pushed from one store, pulled into another; vault keys=[ratings:mdblist, ratings:trakt] |
| ✅ | HEVC Main10 + ASS → Direct Play on HEVC-capable Fire TV profile | hevc Main 10 10-bit → DirectPlay; ASS delivery=Embed |
| ✅ | HEVC Main10 → transcode on H.264-only device (fallback) | Transcode; reasons=[VideoCodecNotSupported, AudioCodecNotSupported] |
| ✅ | ASS kept as styled ASS (External) when transcoding with libass | delivered as Stream.ass with styles + \k tags intact |
| ✅ | ASS burned in by server when the device has no ASS renderer | Transcode; ASS delivery=Encode (burn-in, never flattened to SRT) |
| ⚠️ | 4K HDR10 Direct Play / tone-mapping decisions | not verifiable: this server's probe reported the HDR10 sample as SDR (file is PQ/BT.2020; see ffprobe in CI log). Covered by DeviceProfileTest. |
| ✅ | Dolby Vision DOVIWithHDR10: DV TV / HDR10 TV / SDR TV | hevc DOVIWithHDR10: DV TV=Direct Play, HDR10 TV=Direct Play, SDR TV=server converts |
| ✅ | Dolby Vision DOVI: DV TV / HDR10 TV / SDR TV | hevc DOVI: DV TV=Direct Play, HDR10 TV=server converts, SDR TV=server converts |
| ✅ | Dolby Vision DOVIWithEL: DV TV / HDR10 TV / SDR TV | hevc DOVIWithEL: DV TV=Direct Play, HDR10 TV=Direct Play, SDR TV=server converts |
| ✅ | Recommendations stay per profile (two users, different histories) | A → Recommended For You: Tidewater, Velvet Static, Ember Field, Wavelength Street ‖ B → (nothing that fits B's taste in this small library) |
| ✅ | Audio windows: where the server's mid-file audio starts, and credits edges (diagnostic) | asked for 3:12.000, the audio started at 192.0 s (0 ms off); whole file 240001 ms, runtime 240023 ms; credits rough 213.644–239.769 (ep3 213.644–239.769); refined 215.144–239.394 (true 215–240) |
| ✅ | Intro + credits found by listening (non-anime show, no chapters, no plugin) | episode 2: intro 8.0–37.625 s (true 8–38), credits 215.144–240.023 s (true 215–end); compared with episode 3 (its intro 25.0–54.625 s, episode 3 credits 215.144–239.394 s (true 215–240, scene after)); took 1247 ms [chapters 0 ms, listening 1223 ms, total 1224 ms] |
| ✅ | Outro found on screen (live-action credits, different music every episode, post-credits scene) | episode 2: outro 170.0–211.0 s (true 170–215, scene after it) via credits on screen; took 786 ms [chapters 0 ms, listening 261 ms, screen 523 ms, total 785 ms] |
| ✅ | Two episodes in one file (Paper Town): split by its chapters, played and tracked separately | S01E01-E02 → 1 "null" 240 s, 2 "null" 240 s; part 2 starts at 240.0 s; S01E04-E05 left whole (numbered as episodes 4–5; chapters don't mark the episodes); next after part 1: part 2, after part 2: episode 3; DirectPlay of the file; progress kept per part, server's file untouched |
| ✅ | Episode order (Zephyr Bay): an episode moved back a season, browsed, played next and resumed in that order | season 1: 1 Zephyr Bay - S01E01, 2 Zephyr Bay - S01E02, 3 Zephyr Bay - S01E03, 4 Zephyr Bay - S02E02; season 2: 1 Zephyr Bay - S02E01, 2 Zephyr Bay - S02E03; next after S01E03: Zero Letter, after it: Zephyr Bay - S02E01; Next Up S01E03; progress kept (10 s); DirectPlay of its own file; list 12 ms in Jellyfin's order, seasons + season 1 68 ms in the custom order |
| ✅ | Upcoming episodes request answers (no virtual episodes on this server) | Harbor Precinct: 0 item(s) from /Shows/Upcoming, 0 shown |
| ✅ | MPEG-4/AVI → transcode on Fire TV | Fire TV=Transcode (ContainerNotSupported, VideoCodecNotSupported, AudioCodecNotSupported) |
| ✅ | Multi-track MKV: audio/subtitle selection | 3 audio (aac, ac3, eac3), 3 subs; ja→jpn, forced→English Forced, smart→English Full; DirectPlay |
| ✅ | External SRT sidecar detected | 2 subtitle streams, external=1 |
| ✅ | Direct stream authenticates via header (no token in URL) | HTTP 206, 4096 bytes |
| ✅ | Transcode playlist reachable | #EXTM3U master playlist |
| ✅ | Resume position + watched state sync | resume at 12s, in Continue Watching; watched toggled on/off |
| ✅ | Favorites | added + removed |
| ✅ | Seasons, episodes and Next Up | Harbor Precinct: 1 season, 3 episodes; Next Up after E1 = S1:E2; following = S1:E3 |
| ✅ | Media segments (Skip Intro) endpoint | 0 segments (no intro-detection plugin on CI server) |
