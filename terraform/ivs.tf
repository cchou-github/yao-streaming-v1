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

  # type left at its AWS default: STANDARD - transcoded, full ABR ladder up
  # to 1080p, matching what a browser/OBS-comparable single-rendition stream
  # needs. BASIC is transmux-only, single rendition, lower max bitrate
  # (3.5 Mbps vs 8.5 Mbps) - a real quality tradeoff, not appropriate here
  # since this path is meant to be an equal-footing OBS stand-in, not a
  # degraded one.
  #
  # authorized left at its default (false) - deliberately not the same as
  # "no access control on this channel." IVS's own playback-JWT
  # authorization (authorized=true) is a separate, standalone feature from
  # what's actually used here (the playback restriction policy, attached
  # below via null_resource - neither aws_ivs_channel nor awscc_ivs_channel
  # expose a playback-restriction-policy field at all, confirmed by reading
  # both providers' full resource schemas directly, despite the underlying
  # AWS API supporting it natively via UpdateChannel). Confirmed live, not
  # assumed from CloudFront's own precedent elsewhere in this project: an
  # IVS channel's master manifest is fetchable through CloudFront, but its
  # variant playlists and segments are absolute URLs on IVS's own domains
  # (apn14.playlist.live-video.net and similar) - completely bypassing
  # CloudFront and any signed cookie on it. The playback restriction
  # policy's origin check is what actually gates those requests; JWT auth
  # (authorized=true) would be a second, heavier mechanism doing the same
  # job this project doesn't need on top of it.
  #
  # depends_on is purely an ordering constraint, not a real attribute
  # reference (this resource type has no field to accept one) - confirmed
  # live, the hard way: DeletePlaybackRestrictionPolicy 409s
  # (ConflictException, "still attached to a channel") if attempted before
  # the channel that references it is gone, but DeleteChannel itself
  # succeeds regardless of whether a policy is still attached. Without this,
  # Terraform has no reason to destroy the channel before the policy (they
  # don't otherwise reference each other at all) and could attempt either
  # order, including the one that 409s.
  depends_on = [awscc_ivs_playback_restriction_policy.pool]
}

# Origin/CORS restriction for IVS playback - not IVS's own JWT-based private
# channels (authorized=true), which was considered and rejected: playback
# restriction policies work completely standalone, confirmed against AWS's
# own docs ("If you do not want to use private channels, you can still
# benefit from some of the same protections by leveraging playback
# restriction policies") - no key pair, no per-request token minting, no new
# Java signing code needed. enable_strict_origin_enforcement=true is the
# part that actually matters here: without it, origin restriction only
# applies to the multivariant manifest; with it, every playback request
# (manifest, variant playlist, segments) is checked - the only way this
# closes the gap CloudFront's signed cookies can't reach (see the channel
# resource's own comment above).
#
# allowed_countries deliberately left empty (default: all countries) - this
# project only asked for origin restriction, not geoblocking.
#
# awscc, not aws: confirmed by reading hashicorp/aws v5.100.0's actual
# schema (the latest v5.x release) that aws_ivs_playback_restriction_policy
# does not exist in that provider at all, despite the underlying AWS API
# supporting it - the same class of provider-lag gap MediaPackage v2
# already hit elsewhere in this project, resolved the same way.
resource "awscc_ivs_playback_restriction_policy" "pool" {
  name                             = "${var.project_name}-live-pool-webrtc"
  allowed_origins                  = var.ivs_playback_allowed_origins
  enable_strict_origin_enforcement = true
}

# Attaches the policy to each pool channel - the one thing this file can't
# express declaratively at all: confirmed by reading both aws_ivs_channel's
# and awscc_ivs_channel's full resource schemas directly, neither exposes a
# playback-restriction-policy field, even though UpdateChannel supports it
# natively via the AWS API/CLI. A null_resource + local-exec calling the AWS
# CLI directly is the only way to manage this attachment through Terraform
# at all - a genuinely different mechanism from everything else in this
# directory (imperative, not declarative), used here only because no
# declarative alternative exists in either provider today.
#
# Runs with whatever AWS credentials the operator's own `terraform apply`
# already has - a plain, free control-plane call (ivs:UpdateChannel costs
# nothing, confirmed against AWS's own IVS pricing page's billing
# dimensions - same "control-plane calls are free, only actual streaming
# minutes cost money" reasoning as the rest of this pool), safe to re-run
# unconditionally on every apply (idempotent - reattaching the same policy
# to the same channel is a no-op from AWS's side).
resource "null_resource" "attach_playback_restriction_policy" {
  count = var.ivs_channel_pool_size

  triggers = {
    channel_arn = aws_ivs_channel.pool[count.index].arn
    policy_arn  = awscc_ivs_playback_restriction_policy.pool.arn
  }

  provisioner "local-exec" {
    command = "aws ivs update-channel --region ${var.aws_region} --arn ${aws_ivs_channel.pool[count.index].arn} --playback-restriction-policy-arn ${awscc_ivs_playback_restriction_policy.pool.arn}"
  }
}
