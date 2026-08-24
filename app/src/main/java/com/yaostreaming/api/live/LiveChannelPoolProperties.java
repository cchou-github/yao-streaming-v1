package com.yaostreaming.api.live;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The fixed pool's config, sourced from {@code terraform/medialive.tf}'s
 * {@code live_pool_*} outputs via {@code deploy.sh} - see
 * {@code k8s/aws/app.yaml.template}. Spring binds a comma-separated env var
 * directly into a {@code List<String>} with no extra parsing needed.
 *
 * @param enabled         false everywhere except real AWS - MediaLive has no
 *                        LocalStack equivalent, so this feature stays fully
 *                        inert locally, same pattern as
 *                        {@code app.cloudfront.enabled}.
 * @param poolChannelIds  MediaLive channel ARNs, one per pool slot.
 * @param poolInputIds    MediaLive input ids, index-correlated with
 *                        {@code poolChannelIds} - slot {@code i}'s entry in
 *                        each list belongs to the same channel. What
 *                        {@link MediaLiveChannelPool#reserve} calls
 *                        {@code UpdateInput} on to rotate that slot's ingest
 *                        secret to a fresh random value on every claim,
 *                        rather than handing out a static, indefinitely
 *                        reusable ingest URL the way {@code poolIngestUrls}
 *                        (removed) used to.
 * @param poolOriginSlugs   CloudFront path-prefix slugs ({@code pool-0}..
 *                          {@code pool-N-1}), same index correlation. Also
 *                          what {@link MediaLiveChannelPool#release} passes
 *                          as both {@code channelName} and
 *                          {@code originEndpointName} when resetting a
 *                          slot's MediaPackage origin endpoint -
 *                          {@code terraform/mediapackage.tf} deliberately
 *                          sets both of those to the same {@code pool-N}
 *                          value as this slug.
 * @param channelGroupName  The single MediaPackage channel group shared by
 *                          the whole pool ({@code terraform/mediapackage.tf}'s
 *                          {@code awscc_mediapackagev2_channel_group.pool}) -
 *                          deterministic from {@code var.project_name}, so
 *                          baked directly into {@code app.yaml.template}
 *                          rather than patched by {@code deploy.sh} like
 *                          the per-slot lists above, the same way
 *                          {@code poolOriginSlugs} already is.
 */
@ConfigurationProperties(prefix = "app.live")
public record LiveChannelPoolProperties(
		boolean enabled,
		List<String> poolChannelIds,
		List<String> poolInputIds,
		List<String> poolOriginSlugs,
		String channelGroupName) {
}
