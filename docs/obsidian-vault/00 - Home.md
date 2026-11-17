---
tags: [home]
---
# Sharkfin — Knowledge Base

Sharkfin is a Jellyfin client for **Fire TV / Android TV** (the only supported platform; the
Windows app was removed, see [[Supported Platforms]]), written in Kotlin Multiplatform with Compose
Multiplatform. This vault documents how the app actually works, based on the code in this repository
(`shared/`, `androidApp/`, `ci/`). When the code and a
note disagree, the code wins — and the note should be fixed (see [[How to Update This Vault]]).

> [!tip] Open this folder (`docs/obsidian-vault/`) as a vault in Obsidian. Graph view shows how the
> notes connect; every feature note links to the architecture it relies on.

## Start here
- [[App Overview]] — what Sharkfin is and what it does
- [[Supported Platforms]] · [[Tech Stack]] · [[Project Structure]] · [[Goals and Vision]]
- [[System Architecture]] — the big picture in one diagram
- [[Change Log]] — what changed, update by update

## Features #feature
| Watching | Finding things | You | Getting around |
|---|---|---|---|
| [[Video Player]] | [[Home Page]] | [[User Profiles]] | [[Navigation System]] |
| [[Playback Resume]] | [[Search]] | [[Watch History]] | [[UI Sounds]] |
| [[Subtitles]] | [[Movies and TV Shows]] | [[Ratings]] | [[Server Connection and VPNs]] |
| [[Skip Markers]] | [[Episode Browser]] | | [[App Updates]] |
| | [[Recommendations]] | | |

## Architecture #architecture
[[System Architecture]] · [[Frontend Architecture]] · [[Backend Architecture]] · [[API Endpoints]] ·
[[Data Storage]] · [[Caching Strategy]] · [[Temporary File Lifecycle]] · [[Focus Management]] ·
[[Performance Optimization]]

## UI and design
[[Design System]] · [[Frosted Glass Effects]] · [[Home Page Layout]] · [[Details Page Layout]] ·
[[Navigation Behavior]] · [[Animations and Transitions]]

## Bugs and fixes #bug
[[Known Issues]] · [[Fixed Issues]] · [[Navigation Bugs]] · [[Playback Issues]] · [[UI Layout Issues]]

## Development
[[Development Setup]] · [[Build and Run]] · [[Testing Guide]] · [[Change Log]] ·
[[Future Improvements]] · [[How to Update This Vault]]

## Server and performance #performance
[[Server Overview]] · [[Hardware Limitations]] · [[Streaming Performance]] · [[Resource Usage]] ·
[[Optimization Notes]]
