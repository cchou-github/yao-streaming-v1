# IVS Low-Latency Streaming: the browser/WebRTC go-live path's ingest side,
# coexisting with (not replacing) the MediaLive/RTMP pool in medialive.tf.
# A second, independent fixed pool: IVS and MediaLive are different
# services with different resource types, IAM actions, and lifecycles, so
# there's no way to share one pool between them.
#
# Unlike MediaLive, the app never calls Start/Stop on the channel itself -
# IVS channels are always on, listening for ingest, and cost nothing while
# idle (verified against aws.amazon.com/ivs/pricing/: billed only for actual
# input/output streaming minutes, no per-channel or per-hour base fee). What
# the app does manage at runtime (IvsChannelPool, a later PR) is each
# claim's *stream key* - rotated per-claim via CreateStreamKey/DeleteStreamKey,
# never touched by Terraform. IVS enforces exactly one stream key per channel
# at a time (CreateStreamKey's own docs: "there is a limit of 1 stream key
# per channel"), and there is no reset/rotate action - rotation is
# delete-then-create, confirmed against the real API action list
# (API_Operations.html has no ResetStreamKey/RotateStreamKey).
resource "aws_ivs_channel" "pool" {
  count = var.ivs_channel_pool_size

  name = "${var.project_name}-live-pool-webrtc-${count.index}"

  # type/latency_mode/authorized all left at their AWS defaults:
  #   - type: STANDARD (the default) - transcoded, full ABR ladder up to
  #     1080p, matching what a browser/OBS-comparable single-rendition
  #     stream needs. BASIC is transmux-only, single rendition, lower max
  #     bitrate (3.5 Mbps vs 8.5 Mbps) - a real quality tradeoff, not
  #     appropriate here since this path is meant to be an equal-footing
  #     OBS stand-in, not a degraded one.
  #   - authorized: false (default) - this project's only auth gate is
  #     CloudFront's signed cookies (cloudfront.tf), applied uniformly to
  #     every video/live path; IVS's own playback-JWT authorization would be
  #     a second, redundant gate never exposed to viewers anyway, since they
  #     only ever reach this through CloudFront as an origin, never IVS's
  #     own playback domain directly.
}
