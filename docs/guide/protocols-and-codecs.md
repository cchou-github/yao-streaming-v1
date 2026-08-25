# Protocols, Codecs, and Transcoding

This page is background for the [VOD](vod.md), [RTMP live streaming](live-streaming-rtmp.md), and [WebRTC live streaming](live-streaming-webrtc.md) pages — the concepts here apply across all three, so it's covered once and linked from each.

## Codecs: what the actual video/audio bytes are

A codec compresses raw video/audio into a much smaller stream. This platform standardizes on the same pair everywhere, because it's the combination every modern browser and device can decode without a plugin:

- **H.264 (AVC)** for video — the most broadly compatible video codec in existence. Every path in this platform (uploaded VOD, OBS/RTMP live, browser/WebRTC live) ends up as H.264 by the time it reaches a viewer.
- **AAC** for audio — H.264's usual audio counterpart, equally universal.

Codec choice is independent of the *container* and *transport* — the same H.264/AAC pair gets wrapped differently depending on which pipeline it moves through, described below.

## Transcoding: why source video doesn't go straight to viewers

"Transcoding" means re-encoding a video into a different codec, bitrate, resolution, or container than the source. Two reasons this platform transcodes rather than serving source files directly:

1. **Compatibility** — a phone's raw camera recording or OBS's chosen encoder settings aren't guaranteed to be a browser-playable format on their own.
2. **Delivery format** — playback here is HTTP-based (HLS, described below), not a raw video file, so the source has to be repackaged into segments and a manifest regardless of its original codec.

Every pipeline in this platform currently produces a **single rendition** (one resolution/bitrate) rather than multiple simultaneous quality levels. Adaptive bitrate (ABR) — encoding several renditions and letting the player switch between them based on the viewer's bandwidth — is a natural next step and is covered in [Future Improvements](future-improvements.md).

## Ingest protocols: getting video *into* the platform

Two different protocols are used to push a live video source into the platform, matching the two live-streaming mechanisms:

- **RTMP / RTMPS** (Real-Time Messaging Protocol, optionally over TLS) — a long-established push protocol. A dedicated encoder (OBS, or any RTMP-capable software) opens a persistent connection to an ingest URL and streams video/audio frames continuously. This is what [RTMP live streaming](live-streaming-rtmp.md) uses: OBS pushes to a MediaLive input.
- **WebRTC** — a browser-native, peer-connection-based real-time transport. No external encoder is needed; the browser itself captures the camera/microphone and negotiates a low-latency media connection directly with the ingest endpoint. This is what [WebRTC live streaming](live-streaming-webrtc.md) uses via the AWS IVS Broadcast SDK for Web.

Both protocols are *ingest-only* concerns — once video reaches AWS's transcoding services, delivery to viewers happens over HTTP regardless of which protocol brought it in.

## Delivery format: HLS

**HLS (HTTP Live Streaming)** is how every playback path in this platform — VOD and both live mechanisms — delivers video to viewers. Instead of one continuous video file, HLS breaks a stream into short segments plus a **manifest** (a plain-text playlist) that lists them:

- A **master manifest** describes the available renditions.
- A **variant manifest/playlist** lists the actual segment files (or, for CMAF, byte ranges within a single file) for one rendition, refreshed periodically for live content.
- **Segments** are the actual media chunks a player fetches and plays back to back.

HLS's appeal is that it's just HTTP: segments and manifests are ordinary files a CDN can cache and serve, no special streaming server or persistent connection needed on the viewer's side. Every browser can play it either natively (Safari) or via a small JavaScript library (`hls.js`, used everywhere else in this platform).

Two flavors of HLS show up across this platform's pipelines:

- **Classic HLS with `.ts` segments** — what AWS MediaConvert (VOD transcoding) and MediaLive/MediaPackage v2 (RTMP live) both produce: a two-level manifest (master → variant) referencing `.ts` (MPEG transport stream) segment files.
- **CMAF (Common Media Application Format)** — a newer, fragmented-MP4-based container that AWS IVS uses internally for both its RTMPS and WebRTC ingest paths. CMAF segments (fragmented `.mp4` rather than `.ts`) are what makes low-latency HLS variants possible, since fragments can be published and fetched in smaller pieces than a full traditional segment.

## Where latency comes from

Different combinations of ingest protocol and delivery tuning land at very different end-to-end latency:

| Path | Ingest | Typical glass-to-glass latency |
|---|---|---|
| Traditional HLS (large segments, deep player buffer) | any | 10–30s |
| Low-latency HLS/CMAF, tuned player | any | 2–5s |
| WebRTC, viewer-to-viewer | WebRTC | sub-second |

AWS IVS's "Low-Latency Streaming" product is built on CMAF and is capable of the 2–5s range — but that requires the *player* to actually exploit the low-latency manifest structure. This platform's current player (`hls.js` with a generic, stability-tuned configuration) treats every live manifest the same way it treats a finished VOD file, deliberately buffering a few segments behind the live edge for smooth playback. That buffering is what accounts for the platform's current live latency landing closer to the "tuned low-latency HLS" range's upper end than IVS's theoretical floor — a player-side tuning gap, not a limitation of the underlying transport. See [Future Improvements](future-improvements.md) for what closing that gap would involve.

---

[← Home](../../README.md) · Next: [Infrastructure](infrastructure.md)
