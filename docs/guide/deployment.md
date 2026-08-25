# Deployment

How to actually stand up and run this platform, locally or on real AWS. For what gets provisioned and why, see [Infrastructure](infrastructure.md).

## Required tools

| Tool | Used for |
|---|---|
| [Docker](https://www.docker.com/) + Docker Compose | Local development stack (app, MySQL, LocalStack) |
| [Terraform](https://developer.hashicorp.com/terraform) | Provisioning every AWS resource |
| [AWS CLI](https://aws.amazon.com/cli/) | Authenticating to AWS, and used directly by a couple of deployment scripts |
| `kubectl` | Talking to the EKS cluster once it exists |
| Java + Gradle (via [SDKMAN](https://sdkman.io/)) | Building the application and the Lambda functions |

## AWS account and permissions

Two entirely separate identities are involved, with very different scopes:

1. **The identity that runs `terraform apply`** — this is a human operator's own AWS credentials (an IAM user or an SSO role), configured via `aws configure` or an SSO login. Because Terraform provisions across every service this platform uses — VPC/EC2, EKS, RDS, S3, CloudFront, ECR, IAM itself, MediaConvert, MediaLive, MediaPackage v2, AWS IVS, Lambda, and EventBridge — the simplest setup for a single personal AWS account is an IAM identity with **`AdministratorAccess`**.
2. **The identity the running application itself uses** — a narrow IRSA role Terraform creates automatically, scoped to only the specific API calls the code makes (see [Infrastructure](infrastructure.md#identity-and-access)). This is never the same credentials used to run `terraform apply`, and the operator never needs to configure it manually — Terraform wires it up as part of provisioning.

In short: broad permissions are only ever needed on the machine running Terraform, one time per provision/destroy cycle; the deployed application always runs with much narrower, automatically-provisioned access.

> **Before production**: `AdministratorAccess` is a personal-account convenience, not a production baseline — its blast radius on a leaked credential is the entire account. Replace it with a least-privilege policy scoped to the services above, run through CI/CD under its own role rather than a human's standing credentials (see [Future Improvements](future-improvements.md)).

## Local development

```bash
docker compose up -d
```

Brings up the full local stack — the application, MySQL, and LocalStack (standing in for S3, EventBridge, and Lambda invocation) — with no AWS account needed at all. See [Local Development](local-development.md) for database/seed-data/test commands.

## Provisioning AWS infrastructure

```bash
cd terraform
terraform init
terraform plan
terraform apply
```

This is the one step that actually spends money and takes real, reviewed action — always run `plan` and read it before `apply`. See [Infrastructure](infrastructure.md) for what gets created.

## Building the Lambda functions

```bash
cd lambda
./gradlew shadowJar
```

Produces the jar `terraform apply` expects to find and deploy — Terraform reads whatever jar already exists in `lambda/build/libs/`, it doesn't build it. Run this before `terraform apply` if the Lambda source has changed.

## Deploying the application

```bash
k8s/aws/deploy.sh
```

Run this after `terraform apply` has finished. It builds and pushes the application's container image, reads live values back out of Terraform's own output (database endpoint, CloudFront domain, live-streaming channel identifiers, and so on), applies the Kubernetes manifests, and waits for the rollout to finish. Safe to re-run any time — every step is idempotent.

## Rebuilding from scratch

This platform is designed to be destroyed and rebuilt freely to control cost — `terraform destroy` never gets stuck on non-empty S3 buckets or a non-empty ECR repository. To rebuild: run `terraform apply` again, then `k8s/aws/deploy.sh` again — no special-casing needed, since the deploy script always reads fresh values rather than assuming anything from a previous run still holds.

## Continuous integration

There is currently no CI/CD pipeline — building, testing, and deploying are run manually today. See [Future Improvements](future-improvements.md).

---

[← Home](../../README.md) · Next: [Future Improvements](future-improvements.md)
