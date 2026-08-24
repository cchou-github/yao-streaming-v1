---
name: deploy
description: Provision AWS infrastructure and deploy yao_streaming to it - Terraform apply, building the app image and Lambda jar, and rolling out via Kubernetes. Use when asked to deploy, redeploy, push changes live, or stand the platform up on AWS.
---

# Deploy to AWS

Full detail lives in `docs/guide/deployment.md` and `docs/guide/infrastructure.md` — read those first if this is a fresh session. This skill is the quick sequence once you already know the shape.

## Order of operations

1. **`terraform apply` is a real-money, manual, user-run action.** Never run `terraform plan`/`apply`/`destroy` without the user explicitly asking for that specific step in that moment — writing or editing `.tf` files does not imply permission to apply them. Always run `terraform plan` first and have the user review it before `apply`.
2. If any Lambda source under `lambda/` changed since the last deploy, rebuild the jar Terraform reads from:
   ```
   cd lambda && ./gradlew shadowJar
   ```
3. Once infrastructure is up to date (`terraform apply` has been run by the user), deploy the application:
   ```
   k8s/aws/deploy.sh
   ```
   This builds and pushes the container image, reads live values back out of `terraform output`, applies the Kubernetes manifests, and waits for the rollout. Safe to re-run — every step is idempotent.

## Verifying a deploy landed

```
kubectl get pods
kubectl rollout status deployment/app --timeout=30s
curl -sI "https://$(cd terraform && terraform output -raw cloudfront_domain_name)/login"
```

A 200 on `/login` means the app is reachable through CloudFront. For anything live-streaming-specific (go-live, playback), see the `verify-live-streaming` skill.

## Rebuilding from scratch

This platform is meant to be destroyed and rebuilt freely to control cost. After a `terraform destroy` + `terraform apply` cycle, just run `k8s/aws/deploy.sh` again — no special-casing needed, it always reads fresh values rather than assuming anything from a previous run still holds. `DB_HOST` is the one value that's genuinely different every time (a new RDS instance gets a new endpoint even with the same identifier); the script handles this automatically.
