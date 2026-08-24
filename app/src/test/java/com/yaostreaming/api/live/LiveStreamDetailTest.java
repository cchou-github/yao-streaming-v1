package com.yaostreaming.api.live;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class LiveStreamDetailTest {

	private static LiveStreamDetail detail(StreamStatus status, String playbackUrl) {
		return new LiveStreamDetail(1L, "Ranked queue", null, status, "pool-0", playbackUrl, "Streamer",
				Instant.EPOCH, IngestMode.RTMP);
	}

	@Test
	void isPlayableOnlyWhenLiveAndAUrlExists() {
		assertThat(detail(StreamStatus.LIVE, "http://example/master.m3u8").isPlayable()).isTrue();
		assertThat(detail(StreamStatus.LIVE, null).isPlayable()).isFalse();
		assertThat(detail(StreamStatus.STARTING, "http://example/master.m3u8").isPlayable()).isFalse();
		assertThat(detail(StreamStatus.PENDING, null).isPlayable()).isFalse();
		assertThat(detail(StreamStatus.ENDING, null).isPlayable()).isFalse();
		assertThat(detail(StreamStatus.ENDED, null).isPlayable()).isFalse();
		assertThat(detail(StreamStatus.FAILED, null).isPlayable()).isFalse();
	}

	@Test
	void explainsWhyEachNonLiveStateCannotBeWatched() {
		assertThat(detail(StreamStatus.PENDING, null).statusMessage()).contains("hasn't started");
		assertThat(detail(StreamStatus.STARTING, null).statusMessage()).contains("Starting up");
		assertThat(detail(StreamStatus.ENDING, null).statusMessage()).contains("wrapping up");
		assertThat(detail(StreamStatus.ENDED, null).statusMessage()).contains("ended");
		assertThat(detail(StreamStatus.FAILED, null).statusMessage()).contains("failed");
	}

	@Test
	void flagsTheInconsistentCaseOfLiveWithoutAUrl() {
		assertThat(detail(StreamStatus.LIVE, null).statusMessage())
				.contains("no playback URL");
	}

	@Test
	void saysNothingWhenTheStreamIsActuallyPlayable() {
		assertThat(detail(StreamStatus.LIVE, "http://example/master.m3u8").statusMessage()).isEmpty();
	}

}
