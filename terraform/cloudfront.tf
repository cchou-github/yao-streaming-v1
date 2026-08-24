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

# --- Live playback: routes live/pool-{N}/... to MediaPackage v2 ---
#
# One CloudFront origin + one ordered_cache_behavior covers the whole
# 5-slot pool, not five of each - verified against AWS's own docs rather
# than assumed:
#
# 1. A MediaPackage v2 channel GROUP has exactly one shared "egress domain"
#    (Fn::GetAtt EgressDomain on AWS::MediaPackageV2::ChannelGroup - "The
#    egress domain of the channel group") - every channel/origin-endpoint
#    under it is served from that same hostname, not a per-slot one. So one
#    CloudFront origin covers the whole pool.
# 2. A v2 HLS manifest's real path is fully deterministic, not random:
#    https://{egress-domain}/out/v1/{channel-group-name}/{channel-name}/{origin-endpoint-name}/{manifest-name}.m3u8
#    - every segment is a name this project's own Terraform already chose
#    (mediapackage.tf sets channel_name = origin_endpoint_name = "pool-N"),
#    never a MediaPackage-generated random id the way v1's URLs were.
#
# That real path doesn't match the clean live/pool-N/... convention the
# plan calls for (needed so live traffic can be told apart from VOD's
# *.m3u8/*.ts extension-matched behaviors, which key off file extension
# alone - see this file's header comment). CloudFront's origin_path only
# ever *prepends* to the full incoming request path, it can't replace a
# matched prefix - there's no native "strip this prefix" option on a cache
# behavior. A CloudFront Function is the standard tool for exactly this
# rewrite, and one generic function (parameterized by whatever slug is in
# the URL, not one per slot) covers all 5 slots identically.
resource "aws_cloudfront_function" "live_manifest_rewrite" {
  name    = "${var.project_name}-live-manifest-rewrite"
  runtime = "cloudfront-js-2.0"
  publish = true
  comment = "Rewrites live/pool-N/<file> to MediaPackage v2's real /out/v1/<group>/pool-N/pool-N/<file> path"

  # String methods only, no regex/template literals: CloudFront Functions'
  # JS engine has a restricted feature set, and a JS template literal's
  # ${...} would collide with Terraform's own heredoc interpolation syntax
  # below regardless.
  code = <<-JS
    function handler(event) {
      var request = event.request;
      var uri = request.uri;
      var prefix = '/live/';
      if (uri.substring(0, prefix.length) === prefix) {
        var rest = uri.substring(prefix.length); // "pool-2/master.m3u8"
        var slashIndex = rest.indexOf('/');
        if (slashIndex > -1) {
          var slug = rest.substring(0, slashIndex); // "pool-2"
          var file = rest.substring(slashIndex + 1); // "master.m3u8"
          request.uri = '/out/v1/${awscc_mediapackagev2_channel_group.pool.channel_group_name}/'
              + slug + '/' + slug + '/' + file;
        }
      }
      return request;
    }
  JS
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

  # Live playback - one origin for the whole 5-slot pool. See this file's
  # "Live playback" section (above, next to aws_cloudfront_function) for
  # why a single shared origin is correct here, not five - one per slot.
  origin {
    domain_name = awscc_mediapackagev2_channel_group.pool.egress_domain
    origin_id   = "live-mediapackage"

    custom_origin_config {
      http_port              = 80
      https_port             = 443
      origin_protocol_policy = "https-only" # MediaPackage v2 egress is HTTPS-only
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

  # Live playback, all 5 pool slots at once: path-matched (live/pool-N/...),
  # not extension-matched like the two VOD behaviors below, since a live
  # manifest/segment shares the exact same *.m3u8/*.ts extensions as VOD's
  # but needs a different origin (MediaPackage, not S3) and different
  # caching (disabled - see below). caching_disabled, not caching_optimized:
  # a live manifest updates every few seconds as new segments land, unlike
  # VOD's finished, immutable output.
  #
  # Ordering within this resource is significant and load-bearing:
  # CloudFront picks the FIRST ordered_cache_behavior whose path_pattern
  # matches, not the most specific one - confirmed live, the hard way,
  # against real AWS (aws cloudfront get-distribution-config), after this
  # block sat *after* the VOD *.m3u8/*.ts behaviors below and every live
  # request silently matched *.m3u8 first, routing to the VOD S3 origin and
  # 403ing with an S3-shaped AccessDenied instead of ever reaching
  # MediaPackage. Must stay before both VOD behaviors.
  ordered_cache_behavior {
    path_pattern           = "live/pool-*/*"
    allowed_methods        = ["GET", "HEAD"]
    cached_methods         = ["GET", "HEAD"]
    target_origin_id       = "live-mediapackage"
    viewer_protocol_policy = "redirect-to-https"
    cache_policy_id        = data.aws_cloudfront_cache_policy.caching_disabled.id

    # Same signed-cookie key group as VOD, for consistency (one set of
    # cookies covers both catalog and live once a viewer's watch page sets
    # them) rather than because anything requires it to be shared.
    trusted_key_groups = [aws_cloudfront_key_group.playback.id]

    function_association {
      event_type   = "viewer-request"
      function_arn = aws_cloudfront_function.live_manifest_rewrite.arn
    }
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
