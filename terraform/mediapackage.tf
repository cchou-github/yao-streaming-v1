# MediaPackage v2: takes MediaLive's CMAF Ingest output and repackages it
# as HLS for CloudFront (the CloudFront half lands in a later PR) -
# MediaLive -> MediaPackage -> CloudFront, not MediaLive-direct-to-S3.
#
# v2, not v1: MediaPackage v2's full resource hierarchy (ChannelGroup/
# Channel/OriginEndpoint) is natively available via the awscc provider -
# genuine Terraform resources auto-generated from AWS's Cloud Control API
# schema, not a CloudFormation workaround (unlike the MediaLive channel in
# medialive.tf, which needs one).

# One shared channel group for the whole pool - only Channel/OriginEndpoint
# need to be per-slot, since only they carry the actual ingest/egress URLs.
resource "awscc_mediapackagev2_channel_group" "pool" {
  channel_group_name = "${var.project_name}-live-pool"
  description        = "Fixed live-streaming channel pool"
}

resource "awscc_mediapackagev2_channel" "pool" {
  count = var.live_channel_pool_size

  channel_group_name = awscc_mediapackagev2_channel_group.pool.channel_group_name
  channel_name       = "pool-${count.index}"

  # CMAF Ingest, not HLS ingest - the only input type medialive.tf's
  # CmafIngestGroupSettings output group can push to. ingest_endpoint_urls
  # (computed) is what medialive.tf's CFN-wrapped channel points its
  # Destinations.Settings.Url at.
  input_type = "CMAF"
}

# The resource-based half of ingest authorization: grants MediaLive's
# execution role (medialive.tf) permission to push into this specific
# channel. Confirmed via AWS's own ingest-auth docs
# (docs.aws.amazon.com/mediapackage/latest/userguide/ingest-auth.html):
# MediaPackage v2 requires this channel policy in addition to - not instead
# of - an identity-based IAM policy on the pushing role (see medialive.tf's
# aws_iam_policy.medialive_mediapackage_ingest for that other half).
resource "awscc_mediapackagev2_channel_policy" "pool" {
  count = var.live_channel_pool_size

  channel_group_name = awscc_mediapackagev2_channel_group.pool.channel_group_name
  channel_name       = awscc_mediapackagev2_channel.pool[count.index].channel_name

  policy = jsonencode({
    Version = "2012-10-17"
    Id      = "AllowMediaLiveChannelToIngest"
    Statement = [
      {
        Sid       = "AllowMediaLiveRoleToAccessChannel"
        Effect    = "Allow"
        Principal = { AWS = aws_iam_role.medialive_execution.arn }
        Action    = "mediapackagev2:PutObject"
        Resource  = awscc_mediapackagev2_channel.pool[count.index].arn
      }
    ]
  })
}

resource "awscc_mediapackagev2_origin_endpoint" "pool" {
  count = var.live_channel_pool_size

  channel_group_name   = awscc_mediapackagev2_channel_group.pool.channel_group_name
  channel_name         = awscc_mediapackagev2_channel.pool[count.index].channel_name
  origin_endpoint_name = "pool-${count.index}"

  # CMAF egress container - matches the CMAF ingest side, and is what a
  # browser's hls.js (already vendored for VOD) can play once
  # CloudFront exposes it at live/pool-N/... in a later PR.
  container_type = "CMAF"

  hls_manifests = [
    {
      manifest_name = "master"
    }
  ]
}
