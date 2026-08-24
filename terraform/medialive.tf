# MediaLive: the live-streaming ingest/encode side of the fixed 5-channel
# pool. The app only ever calls
# StartChannel/StopChannel/DescribeChannel against these (see iam.tf's
# app_irsa policy) - CreateChannel/DeleteChannel are never called at
# runtime, only implicitly by `terraform apply`/`destroy`.

# The role MediaLive itself assumes while running a channel - separate from
# app_irsa (iam.tf), which is what the *app* uses to call Start/Stop/
# Describe. Mirrors mediaconvert_execution's shape in mediaconvert.tf.
data "aws_iam_policy_document" "medialive_assume_role" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["medialive.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "medialive_execution" {
  name               = "${var.project_name}-medialive-execution-role"
  assume_role_policy = data.aws_iam_policy_document.medialive_assume_role.json
}

# Identity-side half of ingest authorization (see mediapackage.tf's
# awscc_mediapackagev2_channel_policy for the resource-based half - AWS's
# ingest-auth docs confirm both are required together). Scoped to exactly
# the pool's own MediaPackage channels, same scoping discipline as
# mediaconvert_s3_access/app_s3_access.
data "aws_iam_policy_document" "medialive_mediapackage_ingest" {
  statement {
    sid       = "PutIntoPoolMediaPackageChannels"
    actions   = ["mediapackagev2:PutObject"]
    resources = [for c in awscc_mediapackagev2_channel.pool : c.arn]
  }
}

resource "aws_iam_policy" "medialive_mediapackage_ingest" {
  name   = "${var.project_name}-medialive-mediapackage-ingest"
  policy = data.aws_iam_policy_document.medialive_mediapackage_ingest.json
}

resource "aws_iam_role_policy_attachment" "medialive_mediapackage_ingest" {
  role       = aws_iam_role.medialive_execution.name
  policy_arn = aws_iam_policy.medialive_mediapackage_ingest.arn
}

# RTMP Public, wide open: OBS runs on the streamer's own machine, not
# inside the VPC - same wide-open-by-default posture as
# eks_cluster_endpoint_public_access_cidrs. IAM still gates every AWS API
# call the app itself makes; this only controls who can push an RTMP
# stream into an input once they already have its ingest URL.
resource "aws_medialive_input_security_group" "rtmp_public" {
  whitelist_rules {
    cidr = "0.0.0.0/0"
  }
}

# One RTMP_PUSH input per pool slot - this is the ingest endpoint OBS
# points at. `stream_name` here (and hence the ingest URL Terraform would
# read back) is only ever the value at *creation* time - LiveChannelPool
# overwrites it via UpdateInput on every claim (a fresh random secret, not
# this static "pool-N" placeholder), so the real, current ingest URL for
# any given claim only ever exists at runtime, in the app's own UpdateInput
# response - not something Terraform can meaningfully output or track.
resource "aws_medialive_input" "pool" {
  count = var.live_channel_pool_size

  name                  = "${var.project_name}-live-pool-${count.index}"
  type                  = "RTMP_PUSH"
  input_security_groups = [aws_medialive_input_security_group.rtmp_public.id]

  destinations {
    stream_name = "pool-${count.index}"
  }

  # UpdateInput (called at claim time - see LiveChannelPool.reserve) changes
  # this same field out from under Terraform's own state. Ignored here so
  # a routine `terraform plan` doesn't propose "fixing" it back to
  # "pool-N" and undoing whatever the currently-active claim (if any) set
  # it to.
  lifecycle {
    ignore_changes = [destinations]
  }
}

# --- The channel itself: AWS::MediaLive::Channel via CloudFormation ---
#
# Neither the aws nor awscc Terraform provider can express a CMAF Ingest
# output group on a MediaLive channel: aws_medialive_channel's output-group
# schema is fully closed (archive/hls/media_package[v1 only]/multiplex/
# rtmp/udp - no CMAF variant, no passthrough escape hatch), and awscc has
# no MediaLive channel resource at all (only newer MediaLive Anywhere
# cluster/node/network resources). Verified by reading both providers'
# schemas directly, not assumed.
#
# This wraps the raw AWS::MediaLive::Channel CloudFormation resource
# instead, with a hand-authored EncoderSettings block below. The JSON shape
# was checked against MediaLive's actual API model
# (github.com/boto/botocore's medialive/2017-10-14/service-2.json), not
# guessed - but it's still the one piece of this whole plan that isn't
# provider-schema-validated the way every other resource in this file is.
#
# Do a small isolated `terraform apply` on just this resource (e.g.
# `terraform apply -target='aws_cloudformation_stack.medialive_channel[0]'`)
# before trusting the full pool and building on top of it.
resource "aws_cloudformation_stack" "medialive_channel" {
  count = var.live_channel_pool_size

  name = "${var.project_name}-live-pool-${count.index}"

  template_body = jsonencode({
    AWSTemplateFormatVersion = "2010-09-09"

    Resources = {
      Channel = {
        Type = "AWS::MediaLive::Channel"
        Properties = {
          Name = "${var.project_name}-live-pool-${count.index}"
          # One pipeline, not two - the cheaper choice, consistent with
          # this project's existing rds_multi_az = false cost posture, and
          # what makes "one destination URL per input" hold on the RTMP
          # input side too (see aws_medialive_input's own comment above).
          ChannelClass = "SINGLE_PIPELINE"
          RoleArn      = aws_iam_role.medialive_execution.arn

          InputSpecification = {
            Codec          = "AVC"
            Resolution     = "HD"
            MaximumBitrate = "MAX_10_MBPS"
          }

          InputAttachments = [
            {
              InputId             = aws_medialive_input.pool[count.index].id
              InputAttachmentName = "rtmp-input"
              InputSettings = {
                AudioSelectors = [
                  { Name = "default-audio" }
                ]
              }
            }
          ]

          # Single destination, single Settings entry: SINGLE_PIPELINE
          # channel class means one pipeline, so one ingest URL to push to.
          Destinations = [
            {
              Id = "mediapackage-destination"
              Settings = [
                { Url = awscc_mediapackagev2_channel.pool[count.index].ingest_endpoint_urls[0] }
              ]
            }
          ]

          EncoderSettings = {
            TimecodeConfig = { Source = "SYSTEMCLOCK" }

            AudioDescriptions = [
              {
                Name              = "audio-1"
                AudioSelectorName = "default-audio"
                CodecSettings = {
                  AacSettings = {
                    Bitrate    = 128000
                    SampleRate = 48000
                    CodingMode = "CODING_MODE_2_0"
                  }
                }
              }
            ]

            # One rendition to start, matching LocalFfmpegTranscodePipeline's
            # existing "one rendition, prove the shape" precedent for VOD -
            # an ABR ladder is an explicitly deferred fast-follow.
            VideoDescriptions = [
              {
                Name = "video-1"
                CodecSettings = {
                  H264Settings = {
                    Bitrate         = 3000000
                    RateControlMode = "CBR"
                    GopSize         = 2
                    GopSizeUnits    = "SECONDS"
                    Profile         = "MAIN"
                  }
                }
              }
            ]

            OutputGroups = [
              {
                Name = "cmaf-ingest"
                OutputGroupSettings = {
                  CmafIngestGroupSettings = {
                    Destination = { DestinationRefId = "mediapackage-destination" }
                  }
                }
                # CMAF Ingest is stricter than every other output-group
                # type MediaConvert/MediaLive uses elsewhere in this
                # project: a single Output can carry a video description OR
                # one audio description, never both together - confirmed
                # live, not in any doc, by the isolated apply spike this
                # file's header comment calls for. MediaLive rejected a
                # combined Output with
                # "outputs can only contain a video description, 1 audio
                # description, or 1 caption description"
                # (UnprocessableEntityException). Two Outputs instead, one
                # per description, both still inside the one CMAF output
                # group/destination.
                Outputs = [
                  {
                    OutputName           = "video-output"
                    VideoDescriptionName = "video-1"
                    OutputSettings = {
                      CmafIngestOutputSettings = {
                        NameModifier = "_video"
                      }
                    }
                  },
                  {
                    OutputName            = "audio-output"
                    AudioDescriptionNames = ["audio-1"]
                    OutputSettings = {
                      CmafIngestOutputSettings = {
                        NameModifier = "_audio"
                      }
                    }
                  }
                ]
              }
            ]
          }
        }
      }
    }

    # Re-exported so iam.tf/outputs.tf can reference the channel's ARN
    # without a separate `data` lookup - a stack's own resource attributes
    # aren't visible outside the stack otherwise.
    Outputs = {
      ChannelArn = {
        Value = { "Fn::GetAtt" = ["Channel", "Arn"] }
      }
    }
  })
}
