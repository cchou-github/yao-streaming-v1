# IRSA (IAM Roles for Service Accounts): replaces the static test/test AWS
# credentials used locally against LocalStack (see k8s/app.yaml's
# app-secret) with a real IAM role a k8s ServiceAccount can assume - no
# long-lived keys on the pod at all. Creating that ServiceAccount (with the
# eks.amazonaws.com/role-arn annotation) is a follow-up step alongside
# adapting k8s/app.yaml for real AWS; this only creates the IAM side.

# Scoped to exactly what VideoStorage.java actually calls: GetObject and
# PutObject on object paths of the two named buckets. No ListBucket, no
# Delete*, no bucket-level access, no s3:*.
data "aws_iam_policy_document" "app_s3_access" {
  statement {
    sid     = "AppBucketObjectAccess"
    actions = ["s3:GetObject", "s3:PutObject"]
    resources = [
      "${aws_s3_bucket.raw.arn}/*",
      "${aws_s3_bucket.processed.arn}/*",
    ]
  }
}

resource "aws_iam_policy" "app_s3_access" {
  name   = "${var.project_name}-app-s3-access"
  policy = data.aws_iam_policy_document.app_s3_access.json
}

# Trust policy's sub condition names one exact namespace:serviceaccount
# pair - not a wildcard across all ServiceAccounts in the cluster.
data "aws_iam_policy_document" "irsa_assume_role" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.eks.arn]
    }

    condition {
      test     = "StringEquals"
      variable = "${replace(aws_eks_cluster.main.identity[0].oidc[0].issuer, "https://", "")}:sub"
      values   = ["system:serviceaccount:${var.irsa_namespace}:${var.irsa_service_account_name}"]
    }

    condition {
      test     = "StringEquals"
      variable = "${replace(aws_eks_cluster.main.identity[0].oidc[0].issuer, "https://", "")}:aud"
      values   = ["sts.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "app_irsa" {
  name               = "${var.project_name}-app-irsa-role"
  assume_role_policy = data.aws_iam_policy_document.irsa_assume_role.json
}

resource "aws_iam_role_policy_attachment" "app_irsa_s3" {
  role       = aws_iam_role.app_irsa.name
  policy_arn = aws_iam_policy.app_s3_access.arn
}

# First permission app_irsa gets beyond S3. Deliberately narrow: only the
# three read/lifecycle calls LiveChannelPool ever needs
# (Start/Stop/DescribeChannel) - never Create/Delete, since the pool is a
# fixed, Terraform-managed set of 5 channels the app is only ever allowed
# to turn on and off, not provision or destroy. Scoped to the CFN-wrapped
# channels' own ARNs (medialive.tf), same scoping discipline as
# app_s3_access above.
data "aws_iam_policy_document" "app_medialive_control" {
  statement {
    sid     = "StartStopDescribePoolChannels"
    actions = ["medialive:StartChannel", "medialive:StopChannel", "medialive:DescribeChannel"]
    resources = [
      for s in aws_cloudformation_stack.medialive_channel : s.outputs["ChannelArn"]
    ]
  }

  # UpdateInput: rotates each pool slot's RTMP stream_name to a fresh random
  # secret on every claim (LiveChannelPool.reserve), so the ingest URL
  # handed back to a viewer is a per-claim secret rather than a static,
  # permanently-reusable one tied to the slot itself. Confirmed live before
  # relying on it: UpdateInput works on an input that's still ATTACHED to
  # its channel (no need to detach first) - see PR description for the
  # spike. Scoped to inputs, not channels - a distinct resource type/ARN
  # from the StartChannel/StopChannel statement above.
  statement {
    sid       = "RotatePoolInputSecrets"
    actions   = ["medialive:UpdateInput"]
    resources = [for i in aws_medialive_input.pool : i.arn]
  }
}

resource "aws_iam_policy" "app_medialive_control" {
  name   = "${var.project_name}-app-medialive-control"
  policy = data.aws_iam_policy_document.app_medialive_control.json
}

resource "aws_iam_role_policy_attachment" "app_irsa_medialive" {
  role       = aws_iam_role.app_irsa.name
  policy_arn = aws_iam_policy.app_medialive_control.arn
}

# ResetOriginEndpointState: clears a pool slot's leftover MediaPackage
# content when a stream ends (LiveChannelPool.release), so the next
# claimant's viewers can never be served a previous, unrelated stream's
# video from the shared origin endpoint's manifest window. A separate
# policy/attachment from app_medialive_control above - MediaPackage v2 is a
# distinct service/ARN namespace from MediaLive, not just a different
# action on the same resources.
data "aws_iam_policy_document" "app_mediapackage_control" {
  statement {
    sid       = "ResetPoolOriginEndpoints"
    actions   = ["mediapackagev2:ResetOriginEndpointState"]
    resources = [for e in awscc_mediapackagev2_origin_endpoint.pool : e.arn]
  }
}

resource "aws_iam_policy" "app_mediapackage_control" {
  name   = "${var.project_name}-app-mediapackage-control"
  policy = data.aws_iam_policy_document.app_mediapackage_control.json
}

resource "aws_iam_role_policy_attachment" "app_irsa_mediapackage" {
  role       = aws_iam_role.app_irsa.name
  policy_arn = aws_iam_policy.app_mediapackage_control.arn
}

# IVS control for the browser/WebRTC go-live path (ivs.tf) - a separate
# policy/attachment from the MediaLive ones above, distinct service/ARN
# namespace, same reasoning app_mediapackage_control's own comment gives.
#
# Two statements with genuinely different resource types, not one combined
# statement - confirmed against AWS's IAM action-to-resource-type mapping
# (the Service Authorization Reference's own data, since the live page
# itself is a JS app with no static content to fetch): CreateStreamKey and
# DeleteStreamKey both require a *stream-key* ARN
# (arn:...:ivs:...:stream-key/id), not the channel ARN, even though
# channelArn is CreateStreamKey's own request parameter - StopStream is the
# one that requires a *channel* ARN.
#
# CreateStreamKey/DeleteStreamKey can only be wildcarded to stream-key/* in
# this account/region, not narrowed to the pool's specific channels the way
# app_medialive_control's UpdateInput statement narrows to specific input
# ARNs - a stream key's own ARN is assigned by AWS at creation time and
# carries no reference back to its parent channel, so Terraform has no
# per-channel stream-key ARN to scope to in advance. Accepted gap, not
# missed: StopStream (the action that actually terminates a session) is
# scoped precisely.
data "aws_iam_policy_document" "app_ivs_control" {
  statement {
    sid       = "RotatePoolStreamKeys"
    actions   = ["ivs:CreateStreamKey", "ivs:DeleteStreamKey"]
    resources = ["arn:aws:ivs:${var.aws_region}:${data.aws_caller_identity.current.account_id}:stream-key/*"]
  }

  statement {
    sid       = "StopPoolStreams"
    actions   = ["ivs:StopStream"]
    resources = [for c in aws_ivs_channel.pool : c.arn]
  }
}

resource "aws_iam_policy" "app_ivs_control" {
  name   = "${var.project_name}-app-ivs-control"
  policy = data.aws_iam_policy_document.app_ivs_control.json
}

resource "aws_iam_role_policy_attachment" "app_irsa_ivs" {
  role       = aws_iam_role.app_irsa.name
  policy_arn = aws_iam_policy.app_ivs_control.arn
}
