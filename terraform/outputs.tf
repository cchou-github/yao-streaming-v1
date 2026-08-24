output "aws_region" {
  value = var.aws_region
}

output "vpc_id" {
  value = aws_vpc.main.id
}

output "public_subnet_ids" {
  value = aws_subnet.public[*].id
}

output "private_subnet_ids" {
  value = aws_subnet.private[*].id
}

output "eks_cluster_name" {
  description = "Use with: aws eks update-kubeconfig --region <aws_region> --name <this>"
  value       = aws_eks_cluster.main.name
}

output "eks_cluster_endpoint" {
  value = aws_eks_cluster.main.endpoint
}

output "eks_cluster_certificate_authority_data" {
  value = aws_eks_cluster.main.certificate_authority[0].data
}

output "oidc_provider_arn" {
  value = aws_iam_openid_connect_provider.eks.arn
}

output "irsa_role_arn" {
  description = "Goes directly into the future ServiceAccount's eks.amazonaws.com/role-arn annotation."
  value       = aws_iam_role.app_irsa.arn
}

output "rds_endpoint" {
  value = aws_db_instance.main.endpoint
}

output "rds_address" {
  value = aws_db_instance.main.address
}

output "rds_port" {
  value = aws_db_instance.main.port
}

output "rds_database_name" {
  value = aws_db_instance.main.db_name
}

output "rds_master_username" {
  value = aws_db_instance.main.username
}

output "rds_master_password" {
  description = "Retrieve deliberately with: terraform output -raw rds_master_password"
  value       = random_password.rds_master.result
  sensitive   = true
}

output "raw_bucket_name" {
  value = aws_s3_bucket.raw.bucket
}

output "raw_bucket_arn" {
  value = aws_s3_bucket.raw.arn
}

output "processed_bucket_name" {
  value = aws_s3_bucket.processed.bucket
}

output "processed_bucket_arn" {
  value = aws_s3_bucket.processed.arn
}

output "ecr_repository_url" {
  description = "Use with: docker build/tag/push, then this URL as the k8s Deployment's image."
  value       = aws_ecr_repository.app.repository_url
}

output "alb_dns_name" {
  description = "The app's real public URL: http://<this>"
  value       = aws_lb.app.dns_name
}

output "alb_internal_dns_name" {
  description = "Internal only - not reachable from outside the VPC. Wired into the completion Lambda's CALLBACK_URL env var."
  value       = aws_lb.app_internal.dns_name
}

output "cloudfront_domain_name" {
  description = "Processed-video playback origin: https://<this>/videos/{id}/master.m3u8, once the app-side follow-up sets signed cookies."
  value       = aws_cloudfront_distribution.processed.domain_name
}

output "cloudfront_key_pair_id" {
  description = "Paired with cloudfront_playback_private_key_pem below - identifies which public key CloudFront should verify a signed cookie against."
  value       = aws_cloudfront_public_key.playback.id
}

output "cloudfront_playback_private_key_pem" {
  description = "PKCS#8 PEM - the app signs playback cookies with this. Sensitive: never logged, never displayed by a plain `terraform output`."
  value       = tls_private_key.playback_signing.private_key_pem_pkcs8
  sensitive   = true
}

# Pre-joined as comma-separated strings, not raw lists, so deploy.sh can
# read them with plain `terraform output -raw`, matching every existing
# output above. Index-correlated across all three: pool slot i's channel
# ARN, input id, and origin slug are the i-th entry in each.
output "live_pool_channel_ids" {
  description = "MediaLive channel ARNs, one per pool slot - what LiveChannelPool passes to Start/Stop/DescribeChannel."
  value       = join(",", [for s in aws_cloudformation_stack.medialive_channel : s.outputs["ChannelArn"]])
}

output "live_pool_input_ids" {
  description = "MediaLive input ids, one per pool slot - what LiveChannelPool calls UpdateInput on to rotate each claim's ingest secret. Distinct from live_pool_channel_ids: inputs and channels are separate MediaLive resources with separate ARNs/ids."
  value       = join(",", [for i in aws_medialive_input.pool : i.id])
}

output "live_pool_origin_slugs" {
  description = "CloudFront path-prefix slugs (pool-0..pool-N-1) - deterministic, not derived from any resource, listed here purely for symmetry/documentation with the other two live_pool_* outputs."
  value       = join(",", [for i in range(var.live_channel_pool_size) : "pool-${i}"])
}

# The IVS pool's own equivalents (ivs.tf) - no ivs_pool_input_ids output,
# unlike live_pool_input_ids: IVS channels have no separate "input" resource
# the way MediaLive has channel+input as two distinct things - the channel
# itself is the ingest endpoint. Index-correlated with each other, same as
# the live_pool_* outputs above.
output "ivs_pool_channel_arns" {
  description = "IVS channel ARNs, one per pool slot - what IvsChannelPool passes to CreateStreamKey/StopStream (a later PR)."
  value       = join(",", [for c in aws_ivs_channel.pool : c.arn])
}

output "ivs_pool_ingest_endpoints" {
  description = "IVS channel ingest endpoints, one per pool slot - static per channel (unlike the stream key, which rotates per claim). Exact format/how it's assembled into a full RTMPS/WebRTC publish target is a later PR's concern; this output only wires the raw attribute through."
  value       = join(",", [for c in aws_ivs_channel.pool : c.ingest_endpoint])
}

output "ivs_pool_origin_slugs" {
  description = "CloudFront path-prefix slugs (pool-0..pool-N-1) for the IVS pool - same deterministic-not-resource-derived reasoning as live_pool_origin_slugs above."
  value       = join(",", [for i in range(var.ivs_channel_pool_size) : "pool-${i}"])
}
