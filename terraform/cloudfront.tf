# CloudFront + Origin Access Control in front of the processed bucket, with
# the whole app proxied through the same distribution.
#
# Why CloudFront exists at all: the processed bucket is fully private (see
# s3.tf) and presigned S3 GET URLs turned out not to cover HLS playback
# correctly - MediaConvert's HLS_GROUP_SETTINGS always emits a two-level
# manifest (master playlist -> variant playlist -> segments), and the
# master references the variant by a bare relative filename with no query
# string, so the presigned signature never reaches that follow-up
# request. Confirmed live: the browser 403s on the variant playlist even
# though the master itself loads fine.
#
# CloudFront signed *cookies* don't have this problem: once set, they're
# sent on every request to the distribution's domain regardless of path,
# so every relative reference in a multi-level manifest is covered
# automatically - unlike a presigned URL, which is only ever valid for
# the one path it was signed for.
#
# Why the app (aws_lb.app) is also an origin here, not just the S3 bucket:
# a cookie can only be sent back to the domain that set it. The app lives
# on the ALB's own AWS-generated hostname; CloudFront lives on
# *.cloudfront.net - two unrelated domains, so a Set-Cookie from the app
# could never reach CloudFront on its own. Rather than buying a custom
# domain to share between them, CloudFront becomes the whole site's front
# door: the default behavior proxies everything to the ALB (dynamic,
# uncached), and two extension-matched behaviors (*.m3u8, *.ts) carve out
# the actual video files to the private S3 origin below. See
# CloudFrontCookieSigner.java for where the cookies actually get minted.

resource "aws_cloudfront_origin_access_control" "processed" {
  name                              = "${var.project_name}-processed"
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}

# CloudFront signed cookies need an RSA key pair - generated here rather
# than supplied out-of-band, the same way random_password.rds_master
# already generates the DB master password inside this state file instead
# of requiring a manually-created secret. 2048 bits is CloudFront's
# documented minimum for signer keys.
resource "tls_private_key" "playback_signing" {
  algorithm = "RSA"
  rsa_bits  = 2048
}

resource "aws_cloudfront_public_key" "playback" {
  name        = "${var.project_name}-playback"
  encoded_key = tls_private_key.playback_signing.public_key_pem
}

resource "aws_cloudfront_key_group" "playback" {
  name  = "${var.project_name}-playback"
  items = [aws_cloudfront_public_key.playback.id]
}

data "aws_cloudfront_cache_policy" "caching_optimized" {
  name = "Managed-CachingOptimized"
}

# Dynamic, session-bound app responses (login, catalog, watch pages,
# uploads) can't be cached - this is what the default behavior below uses
# instead of caching_optimized.
data "aws_cloudfront_cache_policy" "caching_disabled" {
  name = "Managed-CachingDisabled"
}

# Forwards everything (cookies, headers, query strings) to the origin
# except the Host header, so the ALB - which has no host-based routing
# rules of its own, only the path-based /internal/* block in alb.tf -
# keeps seeing its own hostname rather than the distribution's.
data "aws_cloudfront_origin_request_policy" "all_viewer_except_host" {
  name = "Managed-AllViewerExceptHostHeader"
}

resource "aws_cloudfront_distribution" "processed" {
  enabled = true
  comment = "${var.project_name} app + processed video playback"

  origin {
    domain_name              = aws_s3_bucket.processed.bucket_regional_domain_name
    origin_id                = "processed-s3"
    origin_access_control_id = aws_cloudfront_origin_access_control.processed.id
  }

  # The app itself - see this file's header comment for why proxying the
  # whole app through CloudFront is what makes signed cookies viable at
  # all, not an unrelated convenience.
  origin {
    domain_name = aws_lb.app.dns_name
    origin_id   = "app-alb"

    custom_origin_config {
      http_port              = 80
      https_port             = 443
      origin_protocol_policy = "http-only" # the ALB has no HTTPS listener yet
      origin_ssl_protocols   = ["TLSv1.2"]
    }
  }

  # Everything not matched by the behaviors below goes to the app - login,
  # catalog, watch pages (HTML), uploads. No trusted_key_groups here: the
  # app handles its own authentication/session, CloudFront just proxies it
  # through.
  default_cache_behavior {
    allowed_methods          = ["GET", "HEAD", "OPTIONS", "PUT", "POST", "PATCH", "DELETE"]
    cached_methods           = ["GET", "HEAD"]
    target_origin_id         = "app-alb"
    viewer_protocol_policy   = "redirect-to-https"
    cache_policy_id          = data.aws_cloudfront_cache_policy.caching_disabled.id
    origin_request_policy_id = data.aws_cloudfront_origin_request_policy.all_viewer_except_host.id
  }

  # The actual video files. Matched by extension rather than a /videos/*
  # path prefix so these never collide with the app's own GET /videos/{id}
  # watch-page route (HTML, no file extension) - that falls through to the
  # default behavior above instead.
  ordered_cache_behavior {
    path_pattern           = "*.m3u8"
    allowed_methods        = ["GET", "HEAD"]
    cached_methods         = ["GET", "HEAD"]
    target_origin_id       = "processed-s3"
    viewer_protocol_policy = "redirect-to-https"
    cache_policy_id        = data.aws_cloudfront_cache_policy.caching_optimized.id

    # This is the actual access control for playback: without a valid
    # signed cookie from this key group, CloudFront returns 403 before
    # the request ever reaches S3. The bucket itself only grants
    # CloudFront (not the public) read access, via this exact
    # distribution's OAC - see aws_s3_bucket_policy.processed_cloudfront_oac
    # below.
    trusted_key_groups = [aws_cloudfront_key_group.playback.id]
  }

  ordered_cache_behavior {
    path_pattern           = "*.ts"
    allowed_methods        = ["GET", "HEAD"]
    cached_methods         = ["GET", "HEAD"]
    target_origin_id       = "processed-s3"
    viewer_protocol_policy = "redirect-to-https"
    cache_policy_id        = data.aws_cloudfront_cache_policy.caching_optimized.id
    trusted_key_groups     = [aws_cloudfront_key_group.playback.id]
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  # Tokyo-region project (see variables.tf's aws_region comment) - include
  # Asia Pacific edge locations rather than the cheaper US/Europe-only
  # PriceClass_100, so playback latency roughly matches the rest of the
  # stack's existing region choice. Still well short of the priciest
  # PriceClass_All.
  price_class = "PriceClass_200"

  viewer_certificate {
    # No ACM cert / custom domain yet (see project roadmap) - the default
    # *.cloudfront.net certificate is fine until one exists.
    cloudfront_default_certificate = true
  }
}

# Grants CloudFront (via this exact distribution's OAC) read access to
# the processed bucket. Replaces the "no bucket policy for now, add an
# OAC-scoped one when CloudFront arrives" state described in s3.tf -
# this is that later step.
resource "aws_s3_bucket_policy" "processed_cloudfront_oac" {
  bucket = aws_s3_bucket.processed.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "AllowCloudFrontServicePrincipalReadOnly"
        Effect    = "Allow"
        Principal = { Service = "cloudfront.amazonaws.com" }
        Action    = "s3:GetObject"
        Resource  = "${aws_s3_bucket.processed.arn}/*"
        Condition = {
          StringEquals = {
            "AWS:SourceArn" = aws_cloudfront_distribution.processed.arn
          }
        }
      }
    ]
  })
}
