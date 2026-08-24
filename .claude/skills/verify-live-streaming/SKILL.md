---
name: verify-live-streaming
description: Ad hoc verification of RTMP (OBS/MediaLive) or WebRTC (browser/AWS IVS) live streaming against a real deployed environment - checking channel/pool state, pod logs, and CloudFront playback. Use when asked to verify, test, or debug a live-streaming go-live/playback/end-stream flow on real AWS.
---

# Verify live streaming against real AWS

This project verifies live-AWS/browser behavior with ad hoc AWS CLI/curl/Playwright checks, not a maintained test script — build the specific check the situation calls for rather than reaching for a fixed suite. Background: `docs/guide/live-streaming-rtmp.md`, `docs/guide/live-streaming-webrtc.md`.

## First move on any unexpected error

Check the application pod's logs — most real bugs found in this project (quota errors, IAM gaps, callback failures) surfaced here first, with the actual AWS exception type and message:

```
kubectl logs deployment/app --tail=400 | grep -n -E "ERROR|Exception:|Caused by:"
```

## RTMP path (MediaLive/MediaPackage)

```
# Pool channel state
aws medialive describe-channel --channel-id <id> --query 'State'

# Confirm the CMAF Ingest -> MediaPackage v2 link and the current ingest URL
aws medialive describe-input --input-id <id> --query 'Destinations'
```

## WebRTC path (AWS IVS)

```
# Does this channel currently have a stream key, and is the playback restriction policy attached?
aws ivs get-channel --arn <channel-arn> --query 'channel.{playbackRestrictionPolicyArn:playbackRestrictionPolicyArn,playbackUrl:playbackUrl}'
aws ivs list-stream-keys --channel-arn <channel-arn>

# Confirm the origin-restriction policy itself
aws ivs get-playback-restriction-policy --arn <policy-arn>

# IVS's edge enforces the policy independently of CloudFront - test it directly
curl -s -o /dev/null -w "%{http_code}\n" "<ivs-playback-url>"                                   # expect 403, no Origin
curl -s -o /dev/null -w "%{http_code}\n" -H "Origin: https://<cloudfront-domain>" "<ivs-playback-url>"  # expect 200
```

## Playback through CloudFront (either path)

```
# Unauthenticated - CloudFront's own signed-cookie check should reject with its native XML error,
# not reach the origin at all (content-type: text/xml, not application/json)
curl -sI "https://<cloudfront-domain>/live/pool-N/master.m3u8"       # RTMP path
curl -sI "https://<cloudfront-domain>/live-webrtc/pool-N/master.m3u8" # WebRTC path
```

If a request with valid signed cookies still 403s with `content-type: application/json`, that's IVS's own edge rejecting it (not CloudFront) — almost always the Origin-header issue above, not a cookie problem.

## Confirming the async confirmation pipeline actually landed

A stream stuck in `STARTING` or `ENDING` longer than expected means the EventBridge → Lambda → internal-ALB callback didn't land. Check the relevant Lambda's own CloudWatch logs, and confirm the EventBridge rule's event pattern still matches what the AWS service actually emits (MediaLive's channel-state-change event and IVS's stream-state-change event have different shapes — see the live-streaming guide pages for the exact fields).
