terraform {
  required_version = ">= 1.7"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
    tls = {
      source  = "hashicorp/tls"
      version = "~> 4.0"
    }
    # MediaPackage v2's resource hierarchy (ChannelGroup/Channel/
    # OriginEndpoint) is only natively available here, not in hashicorp/aws
    # - auto-generated from AWS's Cloud Control API schema. Mixing awscc
    # with aws in one configuration/state is an officially documented,
    # HashiCorp-sanctioned pattern ("AWS and AWSCC Terraform providers:
    # Better together"). See terraform/medialive.tf's own comment for the
    # one piece (the MediaLive channel itself) neither provider can express
    # natively, needing a CloudFormation-wrapped resource instead.
    awscc = {
      source  = "hashicorp/awscc"
      version = "~> 1.0"
    }
  }

  # No backend block: state is local on purpose. terraform/terraform.tfstate
  # holds the RDS master password in plaintext once applied — .gitignore
  # covers it, but treat this directory as sensitive regardless.
}

provider "aws" {
  region = var.aws_region

  default_tags {
    tags = {
      Project     = var.project_name
      Environment = var.environment
      ManagedBy   = "terraform"
    }
  }
}

provider "awscc" {
  region = var.aws_region
}

locals {
  cluster_name = "${var.project_name}-eks"
}
