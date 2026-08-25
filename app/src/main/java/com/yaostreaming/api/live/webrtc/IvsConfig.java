package com.yaostreaming.api.live.webrtc;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.ivs.IvsClient;

/**
 * Mirrors {@code rtmp.MediaLiveConfig}'s shape and reasoning exactly:
 * credentials come from the SDK's default provider chain (IRSA once
 * deployed), no LocalStack/endpoint-override equivalent, fully inert
 * locally via {@code app.ivs.enabled=false}.
 */
@Configuration
public class IvsConfig {

	@Bean
	IvsClient ivsClient() {
		return IvsClient.builder().build();
	}

}
