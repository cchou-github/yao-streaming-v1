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
 * @param poolIngestUrls  RTMP ingest URLs, index-correlated with
 *                        {@code poolChannelIds} - slot {@code i}'s entry in
 *                        each list belongs to the same channel.
 * @param poolOriginSlugs CloudFront path-prefix slugs ({@code pool-0}..
 *                        {@code pool-N-1}), same index correlation.
 */
@ConfigurationProperties(prefix = "app.live")
public record LiveChannelPoolProperties(
		boolean enabled,
		List<String> poolChannelIds,
		List<String> poolIngestUrls,
		List<String> poolOriginSlugs) {
}
