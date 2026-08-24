# Submit/complete transcode Lambdas - see lambda/ (a standalone Gradle
# project, built manually via `cd lambda && ./gradlew shadowJar`, same
# split as the app's Docker image: Terraform deploys the jar, it doesn't
# build it - see README's "Building the transcode Lambdas").
#
# Both handlers implement RequestHandler directly, so `handler` below is
# just the class name (no ::method suffix needed).

data "aws_iam_policy_document" "lambda_assume_role" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["lambda.amazonaws.com"]
    }
  }
}

locals {
  # Holds four handler classes now (submit/complete/live-state-change/
  # ivs-state-change), not two - named for the module, not just the
  # transcode pipeline that used to be its only tenant.
  lambdas_jar = "${path.module}/../lambda/build/libs/transcode-lambdas-0.0.1-SNAPSHOT.jar"
  # MediaConvert's built-in queue every account has - no custom
  # aws_mediaconvert_queue resource needed at this scale.
  mediaconvert_default_queue_arn = "arn:aws:mediaconvert:${var.aws_region}:${data.aws_caller_identity.current.account_id}:queues/Default"
}

# --- Submit Lambda: S3 (raw bucket, uploads/ prefix) -> CreateJob ---

resource "aws_iam_role" "lambda_submit" {
  name               = "${var.project_name}-lambda-submit-role"
  assume_role_policy = data.aws_iam_policy_document.lambda_assume_role.json
}

resource "aws_iam_role_policy_attachment" "lambda_submit_basic" {
  role       = aws_iam_role.lambda_submit.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
}

# Deliberately no s3:GetObject on the raw bucket here: SubmitTranscodeHandler
# never reads the source object itself, it only hands MediaConvert an
# s3:// URI - MediaConvert reads it under mediaconvert_execution's role
# (mediaconvert.tf), not this one.
data "aws_iam_policy_document" "lambda_submit_access" {
  statement {
    # HeadObject against the processed bucket for the dedup check - S3 has
    # no separate HeadObject IAM action, s3:GetObject covers both calls.
    sid       = "DedupCheckProcessedOutput"
    actions   = ["s3:GetObject"]
    resources = ["${aws_s3_bucket.processed.arn}/*"]
  }

  statement {
    # Without this, S3 can't tell an unauthorized caller from a genuinely
    # missing object and returns 403 instead of 404 for HeadObject on a key
    # that doesn't exist yet - which is every first-time dedup check, since
    # nothing has been transcoded yet. SubmitTranscodeHandler only catches
    # NoSuchKeyException (404); without ListBucket every dedup check throws
    # instead. Caught for real: the Lambda fired correctly but every
    # invocation failed on this exact call.
    sid       = "ListProcessedBucketForDedupCheck"
    actions   = ["s3:ListBucket"]
    resources = [aws_s3_bucket.processed.arn]
  }

  statement {
    sid       = "SubmitMediaConvertJobs"
    actions   = ["mediaconvert:CreateJob"]
    resources = ["*"] # CreateJob has no meaningful resource-level scoping without a custom queue/job-template resource.
  }

  statement {
    sid       = "PassMediaConvertExecutionRole"
    actions   = ["iam:PassRole"]
    resources = [aws_iam_role.mediaconvert_execution.arn]
  }
}

resource "aws_iam_policy" "lambda_submit_access" {
  name   = "${var.project_name}-lambda-submit-access"
  policy = data.aws_iam_policy_document.lambda_submit_access.json
}

resource "aws_iam_role_policy_attachment" "lambda_submit_access" {
  role       = aws_iam_role.lambda_submit.name
  policy_arn = aws_iam_policy.lambda_submit_access.arn
}

resource "aws_lambda_function" "submit" {
  function_name    = "${var.project_name}-transcode-submit"
  role             = aws_iam_role.lambda_submit.arn
  runtime          = "java21" # Lambda's current managed Java runtime - no java25 yet, unlike app/'s toolchain.
  handler          = "com.yaostreaming.lambda.SubmitTranscodeHandler"
  filename         = local.lambdas_jar
  source_code_hash = filebase64sha256(local.lambdas_jar)
  timeout          = 30
  memory_size      = 512

  environment {
    variables = {
      PROCESSED_BUCKET       = aws_s3_bucket.processed.bucket
      MEDIACONVERT_ROLE_ARN  = aws_iam_role.mediaconvert_execution.arn
      MEDIACONVERT_QUEUE_ARN = local.mediaconvert_default_queue_arn
    }
  }
}

resource "aws_lambda_permission" "allow_s3_invoke_submit" {
  statement_id   = "AllowS3Invoke"
  action         = "lambda:InvokeFunction"
  function_name  = aws_lambda_function.submit.function_name
  principal      = "s3.amazonaws.com"
  source_arn     = aws_s3_bucket.raw.arn
  source_account = data.aws_caller_identity.current.account_id
}

resource "aws_s3_bucket_notification" "raw_uploads" {
  bucket = aws_s3_bucket.raw.id

  lambda_function {
    lambda_function_arn = aws_lambda_function.submit.arn
    events              = ["s3:ObjectCreated:*"]
    filter_prefix       = "uploads/"
  }

  depends_on = [aws_lambda_permission.allow_s3_invoke_submit]
}

# --- Complete Lambda: EventBridge (MediaConvert Job State Change) -> callback ---

resource "aws_iam_role" "lambda_complete" {
  name               = "${var.project_name}-lambda-complete-role"
  assume_role_policy = data.aws_iam_policy_document.lambda_assume_role.json
}

# Covers both CloudWatch Logs and the ENI create/describe/delete permissions
# a VPC-attached Lambda needs - no S3/MediaConvert access required, this
# Lambda only makes one outbound HTTP call.
resource "aws_iam_role_policy_attachment" "lambda_complete_vpc" {
  role       = aws_iam_role.lambda_complete.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaVPCAccessExecutionRole"
}

# No ingress rule: this Lambda only ever calls out to the internal ALB,
# nothing calls in to it.
resource "aws_security_group" "lambda_complete" {
  name        = "${var.project_name}-lambda-complete-sg"
  description = "Completion Lambda - outbound to the internal ALB only"
  vpc_id      = aws_vpc.main.id

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "${var.project_name}-lambda-complete-sg"
  }
}

resource "aws_lambda_function" "complete" {
  function_name    = "${var.project_name}-transcode-complete"
  role             = aws_iam_role.lambda_complete.arn
  runtime          = "java21"
  handler          = "com.yaostreaming.lambda.CompleteTranscodeHandler"
  filename         = local.lambdas_jar
  source_code_hash = filebase64sha256(local.lambdas_jar)
  timeout          = 30
  memory_size      = 256

  vpc_config {
    subnet_ids         = aws_subnet.private[*].id
    security_group_ids = [aws_security_group.lambda_complete.id]
  }

  environment {
    variables = {
      CALLBACK_URL = "http://${aws_lb.app_internal.dns_name}/internal/transcode/callback"
    }
  }

  depends_on = [aws_iam_role_policy_attachment.lambda_complete_vpc]
}

resource "aws_cloudwatch_event_rule" "mediaconvert_job_state_change" {
  name = "${var.project_name}-mediaconvert-job-state-change"

  event_pattern = jsonencode({
    source      = ["aws.mediaconvert"]
    detail-type = ["MediaConvert Job State Change"]
    detail = {
      status = ["COMPLETE", "ERROR"]
    }
  })
}

resource "aws_cloudwatch_event_target" "complete_lambda" {
  rule = aws_cloudwatch_event_rule.mediaconvert_job_state_change.name
  arn  = aws_lambda_function.complete.arn
}

resource "aws_lambda_permission" "allow_eventbridge_invoke_complete" {
  statement_id  = "AllowEventBridgeInvoke"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.complete.function_name
  principal     = "events.amazonaws.com"
  source_arn    = aws_cloudwatch_event_rule.mediaconvert_job_state_change.arn
}

# --- Live state change Lambda: EventBridge (MediaLive Channel State Change) -> callback ---
#
# Reuses aws_iam_role.lambda_complete and aws_security_group.lambda_complete
# rather than declaring new ones: the trust boundary and required
# permissions (VPC exec role, outbound-only SG, POST to the same internal
# ALB) are identical, not just similar - this Lambda makes no AWS API calls
# of its own either, same as the completion Lambda. Consequence:
# internal-alb.tf needs no changes (its ingress already allows the shared
# SG), alb.tf's block_internal rule already covers /internal/live/* (its
# path_pattern is the /internal/* wildcard, not scoped to
# /internal/transcode/*), and SecurityConfig.java needs zero changes
# (permitAll() + the CSRF exemption already cover /internal/**).

resource "aws_lambda_function" "live_state_change" {
  function_name    = "${var.project_name}-live-state-change"
  role             = aws_iam_role.lambda_complete.arn
  runtime          = "java21"
  handler          = "com.yaostreaming.lambda.LiveStateChangeHandler"
  filename         = local.lambdas_jar
  source_code_hash = filebase64sha256(local.lambdas_jar)
  timeout          = 30
  memory_size      = 256

  vpc_config {
    subnet_ids         = aws_subnet.private[*].id
    security_group_ids = [aws_security_group.lambda_complete.id]
  }

  environment {
    variables = {
      CALLBACK_URL = "http://${aws_lb.app_internal.dns_name}/internal/live/callback"
    }
  }

  depends_on = [aws_iam_role_policy_attachment.lambda_complete_vpc]
}

# detail.state filtered to RUNNING/STOPPED only, not every ChannelState
# value - same "only the outcomes we act on" discipline as
# mediaconvert_job_state_change's COMPLETE/ERROR filter above.
#
# STOPPED, not IDLE: this rule originally filtered on IDLE, based on
# DescribeChannel's own ChannelState enum (which genuinely has no STOPPED
# value - confirmed against the real SDK model). That was the wrong event
# to check, though - this event's detail.state is a plain, unconstrained
# string with its own separate vocabulary, not tied to ChannelState at
# all. Proven live with a temporary catch-all rule logging every real
# transition to CloudWatch Logs: MediaLive actually emits state=RUNNING,
# then state=STOPPING, then state=STOPPED for a normal stop - it never
# emits "IDLE" on this event type, despite DescribeChannel reporting
# IDLE for the exact same channel at the exact same moment. The original
# IDLE filter silently dropped every stop-completion event before it
# ever reached the Lambda - confirmed by CloudWatch metrics showing zero
# rule invocations across dozens of real channel stops this project has
# done, while RUNNING delivered every single time. Channel-level failure
# would be a separate "MediaLive Channel Alert" event, out of scope here.
resource "aws_cloudwatch_event_rule" "medialive_channel_state_change" {
  name = "${var.project_name}-medialive-channel-state-change"

  event_pattern = jsonencode({
    source      = ["aws.medialive"]
    detail-type = ["MediaLive Channel State Change"]
    detail = {
      state = ["RUNNING", "STOPPED"]
    }
  })
}

resource "aws_cloudwatch_event_target" "live_state_change_lambda" {
  rule = aws_cloudwatch_event_rule.medialive_channel_state_change.name
  arn  = aws_lambda_function.live_state_change.arn
}

resource "aws_lambda_permission" "allow_eventbridge_invoke_live_state_change" {
  statement_id  = "AllowEventBridgeInvoke"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.live_state_change.function_name
  principal     = "events.amazonaws.com"
  source_arn    = aws_cloudwatch_event_rule.medialive_channel_state_change.arn
}

# --- IVS state change Lambda: EventBridge (IVS Stream State Change) -> callback ---
#
# The browser/WebRTC go-live path's counterpart to live_state_change above -
# a separate Lambda function rather than one handler branching on
# event.source, since IVS's event shape genuinely differs from MediaLive's
# (see IvsStateChangeHandler's own javadoc). Reuses aws_iam_role.lambda_complete
# and aws_security_group.lambda_complete for the exact same reason
# live_state_change already does - same trust boundary, same one outbound
# call to the same internal ALB. Same consequence too: internal-alb.tf,
# alb.tf's block_internal rule, and SecurityConfig.java all need zero
# changes - already covered by the existing /internal/* wildcard.

resource "aws_lambda_function" "ivs_state_change" {
  function_name    = "${var.project_name}-ivs-state-change"
  role             = aws_iam_role.lambda_complete.arn
  runtime          = "java21"
  handler          = "com.yaostreaming.lambda.IvsStateChangeHandler"
  filename         = local.lambdas_jar
  source_code_hash = filebase64sha256(local.lambdas_jar)
  timeout          = 30
  memory_size      = 256

  vpc_config {
    subnet_ids         = aws_subnet.private[*].id
    security_group_ids = [aws_security_group.lambda_complete.id]
  }

  environment {
    variables = {
      CALLBACK_URL = "http://${aws_lb.app_internal.dns_name}/internal/live/callback"
    }
  }

  depends_on = [aws_iam_role_policy_attachment.lambda_complete_vpc]
}

# detail.event_name filtered to Stream Start/Stream End only, not every
# "IVS Stream State Change" sub-event (Session Created, Session Ended,
# Stream Failure, Stream Takeover, Stream Takeover Failure all share this
# same detail-type) - same "only the outcomes we act on" discipline as
# medialive_channel_state_change's RUNNING/STOPPED filter above.
resource "aws_cloudwatch_event_rule" "ivs_stream_state_change" {
  name = "${var.project_name}-ivs-stream-state-change"

  event_pattern = jsonencode({
    source      = ["aws.ivs"]
    detail-type = ["IVS Stream State Change"]
    detail = {
      event_name = ["Stream Start", "Stream End"]
    }
  })
}

resource "aws_cloudwatch_event_target" "ivs_state_change_lambda" {
  rule = aws_cloudwatch_event_rule.ivs_stream_state_change.name
  arn  = aws_lambda_function.ivs_state_change.arn
}

resource "aws_lambda_permission" "allow_eventbridge_invoke_ivs_state_change" {
  statement_id  = "AllowEventBridgeInvoke"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.ivs_state_change.function_name
  principal     = "events.amazonaws.com"
  source_arn    = aws_cloudwatch_event_rule.ivs_stream_state_change.arn
}
