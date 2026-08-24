# yao_streaming

VOD + live streaming platform: upload, transcode, catalog browsing/watch, and
live broadcast (from a dedicated encoder or straight from a browser). Java
Spring Boot (API + Thymeleaf views), MySQL, Docker, Kubernetes (EKS),
Terraform-managed AWS infrastructure.

This file is git-tracked on purpose — unlike Claude's private per-machine
memory, it travels with the repo to any clone, on any machine. Keep it
updated as the project's actual state, not as a historical log; PR
descriptions and commit messages are where the "why we did X" narrative and
history belong.

## Constraints worth knowing

Cost-conscious by design: infra is meant to be destroyed and rebuilt freely
between sessions (`terraform destroy`, then `terraform apply` +
`k8s/aws/deploy.sh` next time). Don't assume standing infra exists — check
before relying on it. `terraform apply`/`destroy` are real-money, manual,
user-run actions; Claude writes the `.tf` files but doesn't run `apply`
unprompted.

## Current status

- **Foundations**: local dev (Docker Compose + LocalStack), session-backed
  authentication (MySQL-persisted sessions, not per-pod memory), and a first
  real AWS deployment (VPC, EKS, RDS, S3, ECR, a public ALB) are done.
- **VOD**: upload via presigned S3 URL, catalog/watch, and both transcode
  paths are done - an in-process `ffmpeg` pipeline for local dev, and a
  Lambda submit → MediaConvert → EventBridge → Lambda complete →
  internal-ALB callback pipeline for real AWS, verified end-to-end. The app
  is CloudFront-ready (signed-cookie playback, falling back to a presigned
  S3 GET when no CDN is provisioned) but no CDN is provisioned yet - see
  "Not yet built."
- **Not yet built**: a provisioned CloudFront distribution, and live
  streaming (either mechanism).

Check `gh pr list --state open` at the start of a session — open PRs are the
most current source of "what's in flight."

## Resuming on another machine

Everything git-tracked (code, both PR history and open PRs, this file)
travels with a clone. A few things don't and need redoing:

1. `git clone` the repo, `git fetch --prune`, and check `gh pr list --state
   open` for what's currently in flight.
2. Read this file's "Current status" section first - it's kept up to date
   specifically so a fresh session (human or Claude) on a new machine isn't
   starting blind.
3. Set up the Prerequisites in `README.md` (Docker + Docker Compose) and
   confirm `docker compose up -d` works.
4. Configure AWS credentials on the new machine (`aws configure` or SSO,
   whichever you use) and `gh auth login` if you'll be using the `gh` CLI.
5. If resuming infra work: `cd terraform && terraform init`. The state file
   (`terraform.tfstate`) is deliberately gitignored (holds the RDS password
   in plaintext) and never transfers between machines - but since this
   project is meant to be destroyed between sessions anyway, that's usually
   fine to start fresh rather than needing to manually copy it over.

## Where things actually live

- `app/` — Spring Boot app (Gradle, own `Dockerfile`/`Dockerfile.dev`).
- `terraform/` — all AWS infra, one file per concern, heavily commented with
  the *why* behind non-obvious choices (read the comments before assuming
  something is arbitrary).
- `k8s/` vs `k8s/aws/` — local `kind`-cluster manifests vs. the real-AWS
  deployment manifest, deliberately separate rather than templated (see
  `k8s/aws/app.yaml.template`'s own header comment for why).
- `.claude/skills/` — project-specific Claude Code skills encoding this
  project's own established workflows.
