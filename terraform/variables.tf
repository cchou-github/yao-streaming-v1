variable "project_name" {
  description = "Prefix used in resource names and the Project tag."
  type        = string
  default     = "yao-streaming"
}

variable "environment" {
  description = "Environment tag. Single-environment project for now."
  type        = string
  default     = "dev"
}

variable "aws_region" {
  description = "Chosen for latency to Tokyo, not cost."
  type        = string
  default     = "ap-northeast-1"
}

variable "availability_zones" {
  description = "AZ names can vary by account, so this isn't hardcoded elsewhere."
  type        = list(string)
  default     = ["ap-northeast-1a", "ap-northeast-1c"]
}

variable "vpc_cidr" {
  type    = string
  default = "10.0.0.0/16"
}

variable "public_subnet_cidrs" {
  description = "One per AZ. Hosts the NAT Gateway today, the ALB later."
  type        = list(string)
  default     = ["10.0.0.0/24", "10.0.1.0/24"]
}

variable "private_subnet_cidrs" {
  description = "One per AZ. Hosts EKS nodes and RDS."
  type        = list(string)
  default     = ["10.0.10.0/24", "10.0.11.0/24"]
}

variable "single_nat_gateway" {
  description = <<-EOT
    true = one NAT Gateway shared across both private subnets, instead of
    one per AZ. Deliberate cost/HA tradeoff: saves a second NAT Gateway's
    hourly + data charges, at the cost that the private subnet without the
    NAT loses internet egress if that AZ degrades. RDS doesn't need
    egress, so this only risks EKS nodes' outbound access, and only during
    a single-AZ degradation - acceptable for a personal project.
  EOT
  type        = bool
  default     = true
}

variable "eks_cluster_version" {
  description = "Confirm this is still a supported EKS minor at apply time."
  type        = string
  default     = "1.31"
}

variable "eks_cluster_endpoint_public_access_cidrs" {
  description = <<-EOT
    Wide open by default so kubectl works from anywhere with no
    bastion/VPN in scope. IAM still gates actual API calls, but narrow
    this to your own IP (`curl ifconfig.me`) once known.
  EOT
  type        = list(string)
  default     = ["0.0.0.0/0"]
}

variable "eks_node_instance_types" {
  type    = list(string)
  default = ["t3.medium"]
}

variable "eks_node_disk_size" {
  type    = number
  default = 20
}

variable "eks_node_desired_size" {
  description = "RDS replaces the mysql pod entirely now, so this only needs to cover app pod(s) + system daemonsets."
  type        = number
  default     = 1
}

variable "eks_node_min_size" {
  type    = number
  default = 1
}

variable "eks_node_max_size" {
  type    = number
  default = 2
}

variable "rds_instance_class" {
  type    = string
  default = "db.t4g.micro"
}

variable "rds_allocated_storage" {
  type    = number
  default = 20
}

variable "rds_engine_version" {
  description = "Tracks the local mysql:8.4 image used in docker-compose.yml."
  type        = string
  default     = "8.4"
}

variable "rds_multi_az" {
  description = "false halves the cost vs. Multi-AZ - acceptable for a personal project."
  type        = bool
  default     = false
}

variable "rds_backup_retention_period" {
  description = "Days. 1 is the minimum that still enables automated backups/PITR; 0 disables backups entirely."
  type        = number
  default     = 1
}

variable "rds_skip_final_snapshot" {
  description = <<-EOT
    true = fast, unattended `terraform destroy` - matches a
    destroy-and-recreate-between-sessions workflow. A fixed
    final_snapshot_identifier with false would break the *second* destroy,
    since the first destroy's snapshot already exists under that name.
    Flip to false once there's real data worth keeping (snapshot storage
    costs pennies/month either way).
  EOT
  type        = bool
  default     = true
}

variable "db_name" {
  type    = string
  default = "streaming"
}

variable "db_master_username" {
  type    = string
  default = "streaming_admin"
}

variable "raw_bucket_name" {
  description = "Empty string = computed default (project name + account ID suffix, see s3.tf) to dodge the global-bucket-namespace collision risk of a generic name."
  type        = string
  default     = ""
}

variable "processed_bucket_name" {
  description = "Same as raw_bucket_name."
  type        = string
  default     = ""
}

variable "bucket_cors_allowed_origins" {
  description = "Wildcarded for now since no ALB/domain exists yet in this pass to scope it to. Tighten once Ingress is provisioned."
  type        = list(string)
  default     = ["*"]
}

variable "irsa_namespace" {
  description = "Must match whatever the follow-up k8s ServiceAccount ends up using."
  type        = string
  default     = "default"
}

variable "irsa_service_account_name" {
  type    = string
  default = "yao-streaming-app"
}

variable "live_channel_pool_size" {
  description = <<-EOT
    Number of pre-provisioned MediaLive channels. Fixed pool, not
    autoscaled - the app only ever calls
    StartChannel/StopChannel/DescribeChannel against these, never
    CreateChannel/DeleteChannel. Changing this value adds/removes slots on
    the next apply; it does not touch existing ones.
  EOT
  type        = number
  default     = 5
}
