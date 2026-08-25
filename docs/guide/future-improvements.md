# Future Improvements

Known gaps and natural next steps, roughly grouped by area.

## Playback quality and latency

- **Adaptive bitrate (multiple renditions).** Every pipeline currently produces a single rendition. Encoding several bitrate/resolution variants and letting the player switch between them based on viewer bandwidth is the standard next step for both VOD and live playback — see [Protocols, Codecs, and Transcoding](protocols-and-codecs.md#transcoding-why-source-video-doesnt-go-straight-to-viewers).
- **Lower live playback latency.** The player currently treats live manifests the same way it treats finished VOD files, deliberately buffering a few segments behind the live edge for stability. A player genuinely tuned for the underlying transport's low-latency mode — or a dedicated low-latency player build for the WebRTC path specifically — would bring end-to-end latency much closer to what the ingest protocol is actually capable of. See [Protocols, Codecs, and Transcoding](protocols-and-codecs.md#where-latency-comes-from).

## Access control

- **Private/restricted content.** Today, any authenticated viewer can watch any video or live stream — there's no per-content visibility control. Adding one splits into two genuinely different-sized problems:
  - **Private VOD** is comparatively simple: each video already lives at a permanent, unique, never-reused address, so a signed cookie for one video can never leak into another. Adding a visibility flag plus an ownership/invite check at the point the watch page is rendered would be enough on its own.
  - **Private live streams** need more, because the underlying pool-slot addresses (see [RTMP](live-streaming-rtmp.md#the-channel-pool)/[WebRTC](live-streaming-webrtc.md#a-second-independent-pool) live streaming) are reused across many unrelated broadcasts over time. A cookie scoped to a pool slot's path isn't tied to one specific broadcast, so it can't yet distinguish "authorized for this stream" from "authorized for whatever's using this slot right now." Closing that requires a per-broadcast, one-time public address — resolved to the real underlying channel through a small, fast, updatable lookup at the edge (populated when a broadcast starts, cleared when it ends) — layered underneath the same visibility/ownership check described above for VOD.
- **A stronger playback guarantee for the WebRTC path specifically.** The origin-restriction check described in [WebRTC live streaming](live-streaming-webrtc.md#playback-and-why-its-different) is a declared value, not a cryptographic one — it stops casual/incidental access but not a deliberate attempt to fabricate that value directly. A fully closed guarantee here would need the underlying platform to support genuine per-viewer signed playback, which isn't available on this ingest tier today.

## Reliability

- **Reconciliation for stuck uploads.** A video that never reaches a terminal transcoding state (for instance, an upload whose completion event was lost) currently has no automatic sweep to detect and resolve it.
- **Broader reconnect/network-resilience handling** for the WebRTC publish path, beyond whatever the browser broadcast SDK already provides natively out of the box.
- **A deliberately-chosen concurrent live-stream cap.** Both channel pools are currently sized as reasonable placeholders rather than a number derived from an actual capacity or cost target.

## Infrastructure and operations

- **Continuous integration/deployment**, replacing the human operator's manual `terraform apply`/build/deploy steps with a pipeline running under its own scoped role. This is also the natural place to retire the broad `AdministratorAccess` identity described in [Deployment](deployment.md#aws-account-and-permissions) — a least-privilege Terraform role wired into CI, rather than a human holding account-wide access, is what a production rollout should land on.
- **A stable custom domain.** The platform's public address is currently the CDN's own default domain rather than an owned, stable one. Beyond the obvious branding benefit, a stable domain would let the WebRTC path's origin-restriction policy (see [WebRTC live streaming](live-streaming-webrtc.md#playback-and-why-its-different)) be tightened to that exact domain instead of the current, deliberately wider placeholder.
- **Restricting the public load balancer to CDN-only traffic**, so the load balancer's own address can't be used to bypass the CDN layer entirely.
- **Live-to-VOD archival** — automatically turning a finished live broadcast into a permanent, on-demand recording, the same way a video-hosting platform typically retains a replay of a stream after it ends.

## Architecture consolidation worth evaluating

The RTMP live pipeline currently uses two separate AWS services in sequence — MediaLive for ingest/transcoding, MediaPackage v2 for packaging and delivery. Connecting them requires a small piece of infrastructure defined as a raw CloudFormation template rather than native Terraform resources, because the exact output configuration that links the two isn't expressible through the Terraform AWS provider today.

The AWS IVS channel pool backing the WebRTC live path, by contrast, is provisioned entirely through native Terraform resources and already accepts both RTMPS and WebRTC ingest on the same channel — meaning IVS may be capable of covering the RTMP/OBS use case on its own, collapsing MediaLive+MediaPackage into a single service and removing the CloudFormation piece entirely. This is a genuine architectural option, not yet validated: it would need the RTMP ingest path proven against IVS directly before being seriously considered, and it would change the platform's current one-pool-per-mechanism capacity model, so it's worth a dedicated, isolated evaluation rather than an incidental change.

---

[← Home](../../README.md)
