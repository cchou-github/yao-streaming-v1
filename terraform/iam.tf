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
}

resource "aws_iam_policy" "app_medialive_control" {
  name   = "${var.project_name}-app-medialive-control"
  policy = data.aws_iam_policy_document.app_medialive_control.json
}

resource "aws_iam_role_policy_attachment" "app_irsa_medialive" {
  role       = aws_iam_role.app_irsa.name
  policy_arn = aws_iam_policy.app_medialive_control.arn
}
