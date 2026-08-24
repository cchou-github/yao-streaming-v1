# yao_streaming

VOD + live streaming platform: upload, transcode, catalog browsing/watch, and
live broadcast (from a dedicated encoder or straight from a browser). Java
Spring Boot (API + Thymeleaf views), MySQL, Docker, Kubernetes (EKS),
Terraform-managed AWS infrastructure.

This file is git-tracked on purpose — unlike Claude's private per-machine
memory, it travels with the repo to any clone, on any machine. Keep it
updated as the project's actual state, not as a historical log; PR
descriptions and commit messages are where the "why we did X" narrative and
history belong (this project writes unusually detailed ones — `gh pr list`
and `gh pr view <n>` are worth reading before assuming something isn't
explained).

## Constraints worth knowing

Cost-conscious by design: infra is meant to be destroyed and rebuilt freely
between sessions (`terraform destroy`, then `terraform apply` +
`k8s/aws/deploy.sh` next time — see `docs/guide/deployment.md`'s "Rebuilding
from scratch"). Don't assume standing infra exists — check before relying on
it. `terraform apply`/`destroy` are real-money, manual, user-run actions;
Claude writes the `.tf` files but doesn't run `apply` unprompted.

## Current status

- **VOD** (upload via presigned S3 URL, transcode via Lambda + MediaConvert,
  catalog/watch, CloudFront + signed-cookie playback): done.
- **RTMP live streaming** (OBS/any RTMP encoder → a fixed pool of MediaLive
  channels → MediaPackage v2, async EventBridge/Lambda confirmation,
  CloudFront routing, unified live catalog/watch UI): done.
- **WebRTC live streaming** (browser camera → a second, independent fixed
  pool of AWS IVS channels via the IVS Broadcast SDK for Web, async
  confirmation, an IVS Playback Restriction Policy plus CloudFront routing
  for playback, same unified catalog/watch UI as the RTMP path): done.
  Coexists with the RTMP path rather than replacing it — a streamer picks
  one mechanism per broadcast.
- **Browser camera capture via a WHIP-to-RTMP relay (MediaMTX) — attempted
  and abandoned.** Everything up through the relay itself worked as
  designed, but a correctly-transcoded RTMP stream never reliably reached
  MediaLive from it, despite ruling out every content- and protocol-level
  cause investigated (codec correctness, keyframe interval, DTS jitter,
  passthrough vs. re-encode, an FLV sequence-header gap). Superseded by the
  AWS IVS WebRTC path above, which publishes straight into a channel with no
  relay/bridge component at all — avoiding this failure mode by
  construction. Don't re-attempt a WHIP/MediaMTX relay without new
  information.
- **Not yet built**: a CI/CD pipeline (building, testing, and deploying are
  currently manual); a written teardown/rebuild runbook (the process itself
  works, just isn't documented as one); a reconciliation sweep for videos
  stuck in `UPLOADING`; locking the ALB down to CloudFront-only traffic; a
  custom domain/ACM certificate; live-to-VOD archival after a stream ends.
  See `docs/guide/future-improvements.md` for the full list, including
  known security/architecture tradeoffs worth revisiting.

Check `gh pr list --state open` at the start of a session — open PRs are the
most current source of "what's in flight."

## Architecture, briefly

Local dev and the real-AWS path deliberately diverge: locally, the app
itself runs `ffmpeg` in-process against LocalStack (no AWS account needed);
against real AWS, transcoding is entirely offloaded to Lambda + MediaConvert
and the app is never in that path at all. Classes specific to the local-only
path are named with a `Local` prefix (`LocalUploadWatcher`,
`LocalFfmpegTranscodePipeline`, `LocalVideoTranscodeService`) specifically
because an earlier unprefixed version of this code stayed active in the real
AWS deployment and silently broke every upload — don't remove that naming
distinction without understanding why it exists.

Control-plane/data-plane split: the app (EKS/RDS) only ever issues URLs and
records status — it never touches video bytes. Video bytes flow directly
between the browser, S3, MediaConvert/MediaLive/MediaPackage v2/AWS IVS, and
CloudFront. This holds for VOD and both live-streaming paths alike.

See `docs/guide/` (start at the root `README.md`) for the full diagrammed
version: an entity-relationship diagram, per-feature flow diagrams, the AWS
infrastructure layout, and deployment instructions.

## Resuming on another machine

Everything git-tracked (code, both PR history and open PRs, this file)
travels with a clone. A few things don't and need redoing:

1. `git clone` the repo, `git fetch --prune`, and check `gh pr list --state
   open` for what's currently in flight.
2. Read this file's "Current status" section first - it's kept up to date
   specifically so a fresh session (human or Claude) on a new machine isn't
   starting blind. Claude's own private memory/plan state is per-machine and
   does **not** reliably transfer; this file, commit messages, and PR
   descriptions are the portable substitute (this project writes unusually
   detailed ones on purpose).
3. Set up the Prerequisites in `docs/guide/local-development.md` (Docker +
   Docker Compose) and confirm `docker compose up -d` works.
4. Configure AWS credentials on the new machine (`aws configure` or SSO,
   whichever you use) and `gh auth login` if you'll be using the `gh` CLI.
5. If resuming infra work: `cd terraform && terraform init`. The state file
   (`terraform.tfstate`) is deliberately gitignored (holds the RDS password
   in plaintext) and never transfers between machines - but since this
   project is meant to be destroyed between sessions anyway, that's usually
   fine to start fresh rather than needing to manually copy it over. If
   infra is genuinely still standing and you need the *same* state, you'll
   need to copy `terraform.tfstate`/`.tfstate.backup` over yourself through
   a secure channel - never via git.

## Where things actually live

- `app/` — Spring Boot app (Gradle, own `Dockerfile`/`Dockerfile.dev`).
- `lambda/` — submit/complete transcode Lambdas plus the live-streaming
  state-change Lambdas, a fully standalone Gradle project, not a submodule
  of `app/`.
- `terraform/` — all AWS infra, one file per concern, heavily commented with
  the *why* behind non-obvious choices (read the comments before assuming
  something is arbitrary).
- `k8s/` vs `k8s/aws/` — local `kind`-cluster manifests vs. the real-AWS
  deployment manifest, deliberately separate rather than templated (see
  `k8s/aws/app.yaml.template`'s own header comment for why). The
  `.template` file is committed and holds placeholders; `deploy.sh`
  generates the actual `k8s/aws/app.yaml` (gitignored) from it on every
  run and patches in live `terraform output` values, so nothing real
  ever needs to be committed.
- `docs/guide/` — the reader-facing app guide (architecture, data model,
  per-feature flows, deployment). See README.md for concrete commands
  (local dev, tests, deployment).
- `.claude/skills/` — project-specific Claude Code skills (`deploy`,
  `verify-live-streaming`) encoding this project's own established
  workflows, on top of whatever generic AWS/Terraform skills are already
  available.
