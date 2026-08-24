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
