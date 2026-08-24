package com.yaostreaming.api.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.yaostreaming.api.storage.CloudFrontProperties;
import com.yaostreaming.api.user.User;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LiveCatalogServiceTest {

	@Mock
	private StreamRepository streamRepository;

	private static final CloudFrontProperties CLOUDFRONT_DISABLED =
			new CloudFrontProperties(false, null, null, null, Duration.ofMinutes(45));

	private static final CloudFrontProperties CLOUDFRONT_ENABLED =
			new CloudFrontProperties(true, "d123.cloudfront.net", "KEYPAIRID", "unused", Duration.ofMinutes(45));

	private LiveCatalogService service;

	private final User owner = new User("streamer@example.com", "Reiko", "hash");

	@BeforeEach
	void setUp() {
		service = new LiveCatalogService(streamRepository, CLOUDFRONT_ENABLED);
	}

	/** Ids and generated columns are database-assigned, so set them directly. */
	private static Stream stream(long id, String title, StreamStatus status, String originSlug, User owner) {
		Stream stream = new Stream(owner, title);
		stream.setStatus(status);
		stream.setOriginSlug(originSlug);
		set(stream, "id", id);
		return stream;
	}

	private static void set(Object target, String fieldName, Object value) {
		try {
			Field field = target.getClass().getDeclaredField(fieldName);
			field.setAccessible(true);
			field.set(target, value);
		}
		catch (ReflectiveOperationException ex) {
			throw new IllegalStateException(ex);
		}
	}

	@Test
	void mapsEachLiveStreamToASummaryIncludingTheStreamer() {
		Stream live = stream(1L, "Ranked queue", StreamStatus.LIVE, "pool-0", owner);
		when(streamRepository.findLiveOrderByStartedAtDesc()).thenReturn(List.of(live));

		List<LiveStreamSummary> summaries = service.listLive();

		assertThat(summaries).hasSize(1);
		LiveStreamSummary summary = summaries.getFirst();
		assertThat(summary.id()).isEqualTo(1L);
		assertThat(summary.title()).isEqualTo("Ranked queue");
		assertThat(summary.streamerName()).isEqualTo("Reiko");
	}

	@Test
	void returnsAnEmptyListWhenNobodyIsLive() {
		when(streamRepository.findLiveOrderByStartedAtDesc()).thenReturn(List.of());

		assertThat(service.listLive()).isEmpty();
	}

	@Test
	void mapsASingleLiveStreamToItsDetailBuildingAPlainCloudFrontUrl() {
		Stream live = stream(7L, "Ranked queue", StreamStatus.LIVE, "pool-2", owner);
		live.setDescription("Climbing to Diamond");
		when(streamRepository.findById(7L)).thenReturn(Optional.of(live));

		LiveStreamDetail detail = service.findDetail(7L).orElseThrow();

		assertThat(detail.id()).isEqualTo(7L);
		assertThat(detail.description()).isEqualTo("Climbing to Diamond");
		assertThat(detail.originSlug()).isEqualTo("pool-2");
		assertThat(detail.playbackUrl()).isEqualTo("https://d123.cloudfront.net/live/pool-2/master.m3u8");
		assertThat(detail.streamerName()).isEqualTo("Reiko");
		assertThat(detail.isPlayable()).isTrue();
	}

	@Test
	void leavesPlaybackUrlNullWhenNotLiveEvenIfAChannelIsBound() {
		Stream starting = stream(9L, "Warming up", StreamStatus.STARTING, "pool-1", owner);
		when(streamRepository.findById(9L)).thenReturn(Optional.of(starting));

		LiveStreamDetail detail = service.findDetail(9L).orElseThrow();

		assertThat(detail.playbackUrl()).isNull();
		assertThat(detail.isPlayable()).isFalse();
	}

	@Test
	void leavesPlaybackUrlNullWhenCloudFrontIsDisabledEvenIfLive() {
		LiveCatalogService localService = new LiveCatalogService(streamRepository, CLOUDFRONT_DISABLED);
		Stream live = stream(7L, "Ranked queue", StreamStatus.LIVE, "pool-2", owner);
		when(streamRepository.findById(7L)).thenReturn(Optional.of(live));

		LiveStreamDetail detail = localService.findDetail(7L).orElseThrow();

		assertThat(detail.playbackUrl()).isNull();
		assertThat(detail.isPlayable()).isFalse();
	}

	@Test
	void returnsEmptyForAnUnknownStream() {
		when(streamRepository.findById(404L)).thenReturn(Optional.empty());

		assertThat(service.findDetail(404L)).isEmpty();
	}

	@Test
	void findActiveForUserMapsTheUsersOwnChannelBoundStream() {
		Stream starting = stream(9L, "Warming up", StreamStatus.STARTING, "pool-1", owner);
		when(streamRepository.findFirstByUser_IdAndStatusInOrderByCreatedAtDesc(1L, StreamStatus.CHANNEL_BOUND))
				.thenReturn(Optional.of(starting));

		LiveStreamDetail detail = service.findActiveForUser(1L).orElseThrow();

		assertThat(detail.id()).isEqualTo(9L);
		assertThat(detail.status()).isEqualTo(StreamStatus.STARTING);
	}

	@Test
	void findActiveForUserIsEmptyWhenTheUserHasNothingChannelBound() {
		when(streamRepository.findFirstByUser_IdAndStatusInOrderByCreatedAtDesc(1L, StreamStatus.CHANNEL_BOUND))
				.thenReturn(Optional.empty());

		assertThat(service.findActiveForUser(1L)).isEmpty();
	}

}
