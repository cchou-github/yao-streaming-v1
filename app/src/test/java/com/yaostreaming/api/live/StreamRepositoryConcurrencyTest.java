package com.yaostreaming.api.live;

import static org.assertj.core.api.Assertions.assertThat;

import com.yaostreaming.api.TestcontainersConfiguration;
import com.yaostreaming.api.user.User;
import com.yaostreaming.api.user.UserRepository;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proves {@link StreamRepository#claimChannel} actually serializes concurrent
 * claims for the same MediaLive channel slot, under real independently
 * committed transactions — no existing test in this codebase
 * ({@code VideoRepositoryTest} included) exercises a real concurrency race,
 * only single-threaded CAS-as-idempotency.
 *
 * <p>Deliberately not wrapped in a rolled-back {@code @Transactional} (like
 * {@code StreamRepositoryTest}): each racing thread needs its own real
 * transaction against the Testcontainers MySQL instance, not a shared,
 * uncommitted one. Since the MySQL container is reused across separate
 * {@code gradlew test} runs (see {@code TestcontainersConfiguration}), rows
 * are cleaned up explicitly instead, same pattern as
 * {@code SessionPersistenceTest}.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class StreamRepositoryConcurrencyTest {

	private static final String TEST_EMAIL = "racer@example.com";

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private StreamRepository streamRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@AfterEach
	void cleanUp() {
		jdbcTemplate.update("delete from streams where user_id in (select id from users where email = ?)",
				TEST_EMAIL);
		jdbcTemplate.update("delete from users where email = ?", TEST_EMAIL);
	}

	@Test
	void claimChannelAllowsExactlyOneWinnerUnderConcurrentContention() throws Exception {
		User user = userRepository.save(new User(TEST_EMAIL, "Racer", "hashed"));
		String contendedChannelId = "channel-arn-contended";
		int contenders = 10;
		List<Long> streamIds = IntStream.range(0, contenders)
				.mapToObj(i -> streamRepository.save(new Stream(user, "Racer stream " + i)).getId())
				.collect(Collectors.toList());

		TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
		ExecutorService pool = Executors.newFixedThreadPool(contenders);
		CountDownLatch ready = new CountDownLatch(contenders);
		CountDownLatch go = new CountDownLatch(1);
		AtomicInteger successfulClaims = new AtomicInteger();
		try {
			List<Future<?>> races = streamIds.stream()
					.map(streamId -> pool.submit(() -> {
						ready.countDown();
						awaitUninterruptibly(go);
						// Each racing thread needs its own real, independently
						// committed transaction — claimChannel's @Modifying query
						// has no transaction of its own (see StreamRepository's
						// javadoc), same reasoning VideoStatusTransitions documents
						// for why @Modifying calls always need an explicit
						// transactional boundary around them in this codebase.
						try {
							int updated = transactionTemplate.execute(status ->
									streamRepository.claimChannel(streamId, contendedChannelId, "pool-contended"));
							successfulClaims.addAndGet(updated);
						} catch (CannotAcquireLockException deadlockLoser) {
							// Confirmed under real contention: with enough
							// concurrent racers, InnoDB's deadlock detector can
							// pick a losing transaction as a victim and roll it
							// back outright, rather than letting its locking
							// read simply resolve to 0 rows affected. A losing
							// racer here is exactly as valid an outcome as a
							// clean 0 — the channel pool's own reserve() must
							// treat this exception the same as a 0-row result
							// and move on to the next candidate channel.
						}
					}))
					.collect(Collectors.toList());

			assertThat(ready.await(10, TimeUnit.SECONDS)).as("all racers should reach the starting line").isTrue();
			go.countDown();
			for (Future<?> race : races) {
				race.get(10, TimeUnit.SECONDS);
			}
		} finally {
			pool.shutdown();
		}

		assertThat(successfulClaims.get()).as("exactly one racer should win the shared channel").isEqualTo(1);

		long startingCount = streamIds.stream()
				.map(id -> streamRepository.findById(id).orElseThrow())
				.filter(stream -> stream.getStatus() == StreamStatus.STARTING)
				.count();
		assertThat(startingCount).isEqualTo(1);
	}

	private static void awaitUninterruptibly(CountDownLatch latch) {
		try {
			latch.await();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RuntimeException(e);
		}
	}

}
