package com.yaostreaming.api.live.webrtc;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The fixed IVS pool's config, sourced from {@code terraform/ivs.tf}'s
 * {@code ivs_pool_*} outputs via {@code deploy.sh} - see
 * {@code k8s/aws/app.yaml.template}. Mirrors
 * {@code rtmp.MediaLiveChannelPoolProperties}'s shape, with two real
 * differences rather than a straight copy: no {@code poolInputIds}
 * equivalent (an IVS channel has no separate "input" resource the way
 * MediaLive does - the channel itself is the ingest endpoint), and no
 * {@code channelGroupName} equivalent (that was MediaPackage v2-specific;
 * IVS has no comparable shared parent resource to reference).
 *
 * @param enabled              false everywhere except real AWS - IVS has no
 *                             LocalStack equivalent, same pattern as
 *                             {@code app.live.enabled}.
 * @param poolChannelArns      IVS channel ARNs, one per pool slot - what
 *                             {@link IvsChannelPool} passes to
 *                             CreateStreamKey/DeleteStreamKey/StopStream.
 * @param poolIngestEndpoints  IVS channel ingest endpoints, index-correlated
 *                             with {@code poolChannelArns} - static per
 *                             channel, unlike the stream key, which rotates
 *                             on every claim (see {@link IvsChannelPool}).
 * @param poolOriginSlugs      CloudFront path-prefix slugs ({@code pool-0}..
 *                             {@code pool-N-1}), same index correlation.
 */
@ConfigurationProperties(prefix = "app.ivs")
public record IvsChannelPoolProperties(
		boolean enabled,
		List<String> poolChannelArns,
		List<String> poolIngestEndpoints,
		List<String> poolOriginSlugs) {
}
