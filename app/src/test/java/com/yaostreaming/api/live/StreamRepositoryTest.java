package com.yaostreaming.api.live;

import static org.assertj.core.api.Assertions.assertThat;

import com.yaostreaming.api.TestcontainersConfiguration;
import com.yaostreaming.api.user.User;
import com.yaostreaming.api.user.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single-threaded CAS-as-idempotency checks for {@link StreamRepository}.
 * The real concurrent-contention guarantee is exercised separately in
 * {@link StreamRepositoryConcurrencyTest}, which needs real independently
 * committed transactions rather than this class's rolled-back one.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class StreamRepositoryTest {

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private StreamRepository streamRepository;

	@Autowired
	private EntityManager entityManager;

	@Test
	void savesAndLoadsStreamWithStatusAndUser() {
		User user = userRepository.save(new User("streamer@example.com", "Streamer", "hashed"));

		Stream saved = streamRepository.save(new Stream(user, "My First Broadcast"));

		// Force a real read from the DB instead of returning the cached
		// in-memory instance, so DB-generated defaults (created_at) are populated.
		entityManager.flush();
		entityManager.clear();

		Stream loaded = streamRepository.findById(saved.getId()).orElseThrow();

		assertThat(loaded.getStatus()).isEqualTo(StreamStatus.PENDING);
		assertThat(loaded.getUser().getEmail()).isEqualTo("streamer@example.com");
		assertThat(loaded.getCreatedAt()).isNotNull();
	}

	@Test
	void claimChannelMovesAPendingStreamToStartingAndBindsTheChannel() {
		User user = userRepository.save(new User("claimer@example.com", "Claimer", "hashed"));
		Stream stream = streamRepository.save(new Stream(user, "Broadcast"));

		int updated = streamRepository.claimChannel(stream.getId(), "channel-arn-0", "pool-0");

		assertThat(updated).isEqualTo(1);
		entityManager.flush();
		entityManager.clear();
		Stream loaded = streamRepository.findById(stream.getId()).orElseThrow();
		assertThat(loaded.getStatus()).isEqualTo(StreamStatus.STARTING);
		assertThat(loaded.getChannelId()).isEqualTo("channel-arn-0");
		assertThat(loaded.getOriginSlug()).isEqualTo("pool-0");
	}

	@Test
	void claimChannelIsANoOpWhenTheChannelIsAlreadyHeldByAnActiveStream() {
		User user = userRepository.save(new User("blocked@example.com", "Blocked", "hashed"));
		Stream holder = streamRepository.save(new Stream(user, "Already live"));
		streamRepository.claimChannel(holder.getId(), "channel-arn-1", "pool-1");

		Stream contender = streamRepository.save(new Stream(user, "Wants the same channel"));
		int updated = streamRepository.claimChannel(contender.getId(), "channel-arn-1", "pool-1");

		assertThat(updated).isEqualTo(0);
		entityManager.flush();
		entityManager.clear();
		Stream loaded = streamRepository.findById(contender.getId()).orElseThrow();
		assertThat(loaded.getStatus()).isEqualTo(StreamStatus.PENDING);
		assertThat(loaded.getChannelId()).isNull();
	}

	@Test
	void claimChannelIsANoOpWhenTheStreamIsNoLongerPending() {
		User user = userRepository.save(new User("already-started@example.com", "AlreadyStarted", "hashed"));
		Stream stream = streamRepository.save(new Stream(user, "Broadcast"));
		streamRepository.claimChannel(stream.getId(), "channel-arn-2", "pool-2");

		// Simulates a caller retrying against a stream that already claimed a
		// (possibly different) channel.
		int updated = streamRepository.claimChannel(stream.getId(), "channel-arn-3", "pool-3");

		assertThat(updated).isEqualTo(0);
		entityManager.flush();
		entityManager.clear();
		Stream loaded = streamRepository.findById(stream.getId()).orElseThrow();
		assertThat(loaded.getChannelId()).isEqualTo("channel-arn-2");
	}

	@Test
	void claimChannelAllowsTheSameChannelToBeReclaimedOnceTheHolderIsNoLongerActive() {
		User user = userRepository.save(new User("reclaimer@example.com", "Reclaimer", "hashed"));
		Stream previousHolder = streamRepository.save(new Stream(user, "Yesterday's broadcast"));
		streamRepository.claimChannel(previousHolder.getId(), "channel-arn-4", "pool-4");
		previousHolder.setStatus(StreamStatus.ENDED);
		streamRepository.save(previousHolder);
		entityManager.flush();
		entityManager.clear();

		Stream nextStream = streamRepository.save(new Stream(user, "Today's broadcast"));
		int updated = streamRepository.claimChannel(nextStream.getId(), "channel-arn-4", "pool-4");

		assertThat(updated).isEqualTo(1);
	}

}
