#!/usr/bin/env bash
# Full redeploy against real AWS: run this any time after a `terraform
# apply` in terraform/ - especially after a `terraform destroy` + `apply`
# cycle, since RDS gets a new endpoint/password and ECR's images are wiped
# (force_delete = true) every time. Safe to re-run any time, even when
# nothing actually changed - every step here is idempotent.
#
# Does NOT run `terraform apply` itself - that's a real-money step this
# project has kept as an explicit, reviewed, manual action all along (see
# terraform/*.tf's comments). This script only picks up from wherever
# terraform/ currently stands and gets the app running to match it.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
TF_DIR="$REPO_ROOT/terraform"
APP_YAML_TEMPLATE="$SCRIPT_DIR/app.yaml.template"
APP_YAML="$SCRIPT_DIR/app.yaml"
REGION="ap-northeast-1"
CLUSTER="yao-streaming-eks"

echo "==> Reading terraform outputs"
ECR_URL=$(terraform -chdir="$TF_DIR" output -raw ecr_repository_url)
RDS_ADDRESS=$(terraform -chdir="$TF_DIR" output -raw rds_address)
RDS_PASSWORD=$(terraform -chdir="$TF_DIR" output -raw rds_master_password)
CLOUDFRONT_DOMAIN=$(terraform -chdir="$TF_DIR" output -raw cloudfront_domain_name)
CLOUDFRONT_KEY_PAIR_ID=$(terraform -chdir="$TF_DIR" output -raw cloudfront_key_pair_id)
CLOUDFRONT_PRIVATE_KEY=$(terraform -chdir="$TF_DIR" output -raw cloudfront_playback_private_key_pem)
LIVE_POOL_CHANNEL_IDS=$(terraform -chdir="$TF_DIR" output -raw live_pool_channel_ids)
LIVE_POOL_INPUT_IDS=$(terraform -chdir="$TF_DIR" output -raw live_pool_input_ids)
IVS_POOL_CHANNEL_ARNS=$(terraform -chdir="$TF_DIR" output -raw ivs_pool_channel_arns)
IVS_POOL_INGEST_ENDPOINTS=$(terraform -chdir="$TF_DIR" output -raw ivs_pool_ingest_endpoints)

echo "==> Refreshing kubeconfig (cluster may be new since the last apply)"
aws eks update-kubeconfig --region "$REGION" --name "$CLUSTER" >/dev/null

echo "==> Resetting k8s/aws/app.yaml from app.yaml.template (gitignored - the"
echo "    template is the only copy of this file meant to be committed)"
cp "$APP_YAML_TEMPLATE" "$APP_YAML"

echo "==> Building and pushing the image (ECR's images are wiped on every"
echo "    destroy - force_delete=true - so this can't be skipped safely)"
aws ecr get-login-password --region "$REGION" \
  | docker login --username AWS --password-stdin "$ECR_URL" >/dev/null
docker build -q -t "$ECR_URL:latest" -f "$REPO_ROOT/app/Dockerfile" "$REPO_ROOT/app" >/dev/null
docker push -q "$ECR_URL:latest" >/dev/null

echo "==> Patching DB_HOST in k8s/aws/app.yaml (the one value that changes"
echo "    on RDS recreation - bucket names/IRSA ARN/ECR URL are deterministic)"
sed -i "s|DB_HOST:.*|DB_HOST: $RDS_ADDRESS|" "$APP_YAML"
sed -i "s|until nc -z .* 3306|until nc -z $RDS_ADDRESS 3306|" "$APP_YAML"

echo "==> Patching CLOUDFRONT_DOMAIN_NAME/KEY_PAIR_ID (also not stable across"
echo "    a destroy+apply cycle - a new distribution gets a new domain/key pair)"
sed -i "s|CLOUDFRONT_DOMAIN_NAME:.*|CLOUDFRONT_DOMAIN_NAME: $CLOUDFRONT_DOMAIN|" "$APP_YAML"
sed -i "s|CLOUDFRONT_KEY_PAIR_ID:.*|CLOUDFRONT_KEY_PAIR_ID: $CLOUDFRONT_KEY_PAIR_ID|" "$APP_YAML"

echo "==> Patching LIVE_POOL_CHANNEL_IDS/INPUT_IDS (also not stable across a"
echo "    destroy+apply cycle - a new pool gets new channel ARNs/input ids)"
sed -i "s|LIVE_POOL_CHANNEL_IDS:.*|LIVE_POOL_CHANNEL_IDS: $LIVE_POOL_CHANNEL_IDS|" "$APP_YAML"
sed -i "s|LIVE_POOL_INPUT_IDS:.*|LIVE_POOL_INPUT_IDS: $LIVE_POOL_INPUT_IDS|" "$APP_YAML"

echo "==> Patching IVS_POOL_CHANNEL_ARNS/INGEST_ENDPOINTS (same reasoning -"
echo "    a new pool gets new channel ARNs/ingest endpoints)"
sed -i "s|IVS_POOL_CHANNEL_ARNS:.*|IVS_POOL_CHANNEL_ARNS: $IVS_POOL_CHANNEL_ARNS|" "$APP_YAML"
sed -i "s|IVS_POOL_INGEST_ENDPOINTS:.*|IVS_POOL_INGEST_ENDPOINTS: $IVS_POOL_INGEST_ENDPOINTS|" "$APP_YAML"

echo "==> Recreating app-secret with the current RDS password + CloudFront signing key"
kubectl create secret generic app-secret \
  --from-literal=DB_PASSWORD="$RDS_PASSWORD" \
  --from-literal=CLOUDFRONT_PRIVATE_KEY="$CLOUDFRONT_PRIVATE_KEY" \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null

echo "==> Applying k8s/aws/app.yaml"
kubectl apply -f "$APP_YAML"

# The image tag is always "latest" - if the manifest text is otherwise
# byte-identical to what's already applied (the common case for a
# code-only fix with no config changes), `kubectl apply` sees no diff and
# never triggers a new rollout at all, silently leaving the old pod
# running old code. Forcing a restart explicitly, every time, is what
# actually guarantees the freshly-pushed image gets used - relying on
# imagePullPolicy: Always alone isn't enough if no new pod ever gets
# scheduled in the first place. Caught for real: a redeploy "succeeded"
# but the pod kept serving code from hours earlier.
echo "==> Forcing a rollout restart (image tag is mutable - apply alone can't be trusted to notice new content)"
kubectl rollout restart deployment/app

echo "==> Waiting for the app pod to become ready"
kubectl rollout status deployment/app --timeout=180s

# Seeding must come after the app is up, not before: the users table only
# exists once Flyway has run, and Flyway only runs on app startup. Seeding
# earlier (as an initial version of this script did) fails with "Table
# 'streaming.users' doesn't exist" against a freshly (re)created RDS.
echo "==> Seeding the dev user (idempotent - safe even if it already exists)"
kubectl run mysql-seed-"$(date +%s)" --rm -i --image=mysql:8.4 --restart=Never -- \
  mysql -h "$RDS_ADDRESS" -u streaming_admin -p"$RDS_PASSWORD" streaming \
  < "$REPO_ROOT/app/src/main/resources/db/seed/dev_user.sql"

echo "==> Done. kubectl port-forward svc/app 8080:8080 to reach it in a browser."
