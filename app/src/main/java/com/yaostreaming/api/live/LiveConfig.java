package com.yaostreaming.api.live;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.medialive.MediaLiveClient;

/**
 * Credentials come from the SDK's default provider chain (IRSA once
 * deployed - see {@code S3Config}'s own javadoc for the fuller explanation).
 * No LocalStack/endpoint-override equivalent here: MediaLive has none, so
 * this feature is fully inert locally via {@code app.live.enabled=false} -
 * nothing in the app calls {@code reserve}/{@code release} in that case, so
 * the client is built but never actually used to make a request. Region is
 * left to the SDK's own default resolution (picks up the {@code AWS_REGION}
 * env var {@code k8s/aws/app.yaml.template} already sets) rather than a
 * second {@code app.live.region} property duplicating
 * {@code app.storage.region}.
 */
@Configuration
public class LiveConfig {

	@Bean
	MediaLiveClient mediaLiveClient() {
		return MediaLiveClient.builder().build();
	}

}
