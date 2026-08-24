# The role MediaConvert itself assumes to read the source file and write HLS
# output - separate from the submit Lambda's own role (iam:PassRole in
# lambda.tf hands this ARN to CreateJob; MediaConvert assumes it directly,
# the Lambda's own credentials never touch the video bytes).

data "aws_iam_policy_document" "mediaconvert_assume_role" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["mediaconvert.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "mediaconvert_execution" {
  name               = "${var.project_name}-mediaconvert-execution-role"
  assume_role_policy = data.aws_iam_policy_document.mediaconvert_assume_role.json
}

# Same scoping discipline as app_s3_access in iam.tf: exactly the two calls
# MediaConvert actually needs, nothing bucket-level.
data "aws_iam_policy_document" "mediaconvert_s3_access" {
  statement {
    sid       = "ReadRawSource"
    actions   = ["s3:GetObject"]
    resources = ["${aws_s3_bucket.raw.arn}/*"]
  }

  statement {
    sid       = "WriteProcessedOutput"
    actions   = ["s3:PutObject"]
    resources = ["${aws_s3_bucket.processed.arn}/*"]
  }
}

resource "aws_iam_policy" "mediaconvert_s3_access" {
  name   = "${var.project_name}-mediaconvert-s3-access"
  policy = data.aws_iam_policy_document.mediaconvert_s3_access.json
}

resource "aws_iam_role_policy_attachment" "mediaconvert_s3" {
  role       = aws_iam_role.mediaconvert_execution.name
  policy_arn = aws_iam_policy.mediaconvert_s3_access.arn
}
