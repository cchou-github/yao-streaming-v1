package com.yaostreaming.api.live;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Assembles every bean implementing {@link LiveChannelPool} into a map keyed
 * by {@link LiveChannelPool#supportedMode()}, so {@link LiveStreamingService}
 * can route a request to the right pool without a chain of instanceof
 * checks or string-keyed {@code @Qualifier} beans - Spring auto-populates
 * the {@code List<LiveChannelPool>} constructor parameter with every
 * matching bean.
 *
 * <p>Didn't exist before this PR because it didn't need to: with only one
 * {@code LiveChannelPool} implementation, a single autowired field worked
 * fine. The moment a second one ({@code webrtc.IvsChannelPool}) exists as a
 * bean, that single-field autowiring becomes ambiguous
 * ({@code NoUniqueBeanDefinitionException}, confirmed the hard way while
 * building {@code IvsChannelPool} itself, before this class existed) - this
 * and {@code IvsChannelPool}'s {@code @Component} annotation are the two
 * changes that resolve it, landing together.
 */
@Configuration
public class LiveChannelPoolConfig {

	@Bean
	Map<IngestMode, LiveChannelPool> liveChannelPoolsByMode(List<LiveChannelPool> pools) {
		return pools.stream().collect(Collectors.toMap(LiveChannelPool::supportedMode, Function.identity()));
	}

}
