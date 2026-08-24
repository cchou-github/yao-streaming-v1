package com.yaostreaming.api.live;

import com.yaostreaming.api.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "streams")
public class Stream {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "user_id", nullable = false)
	private User user;

	@Column(nullable = false, length = 255)
	private String title;

	@Column(length = 2000)
	private String description;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private StreamStatus status;

	@Enumerated(EnumType.STRING)
	@Column(name = "ingest_mode", nullable = false, length = 20)
	private IngestMode ingestMode;

	@Column(name = "channel_id", length = 255)
	private String channelId;

	@Column(name = "origin_slug", length = 50)
	private String originSlug;

	/**
	 * The IVS stream key's own ARN - {@code null} for RTMP streams. Needed
	 * only so {@code IvsChannelPool.confirmStopped} knows which key to
	 * {@code DeleteStreamKey} once the async stop confirmation lands
	 * (rotation is delete-then-create, not a single reset call - IVS has no
	 * API for that). Not nulled once set - same "leave it for audit
	 * history" reasoning {@code channel_id}'s own migration comment
	 * documents for itself.
	 */
	@Column(name = "ingest_secret_arn", length = 255)
	private String ingestSecretArn;

	@Column(name = "started_at")
	private Instant startedAt;

	@Column(name = "ended_at")
	private Instant endedAt;

	@Column(name = "created_at", insertable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", insertable = false, updatable = false)
	private Instant updatedAt;

	protected Stream() {
	}

	/**
	 * Defaults to {@link IngestMode#RTMP}, for callers/tests that don't care
	 * which mechanism a stream uses. {@link #Stream(User, String, IngestMode)}
	 * is the explicit form the dual-mode {@code goLive} calls in production,
	 * where the mechanism is a real, caller-supplied choice.
	 */
	public Stream(User user, String title) {
		this(user, title, IngestMode.RTMP);
	}

	public Stream(User user, String title, IngestMode ingestMode) {
		this.user = user;
		this.title = title;
		this.status = StreamStatus.PENDING;
		this.ingestMode = ingestMode;
	}

	public Long getId() {
		return id;
	}

	public User getUser() {
		return user;
	}

	public String getTitle() {
		return title;
	}

	public void setTitle(String title) {
		this.title = title;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public StreamStatus getStatus() {
		return status;
	}

	public void setStatus(StreamStatus status) {
		this.status = status;
	}

	/** Fixed at creation, never changes over a stream's lifecycle - getter only, deliberately no setter. */
	public IngestMode getIngestMode() {
		return ingestMode;
	}

	public String getIngestSecretArn() {
		return ingestSecretArn;
	}

	public void setIngestSecretArn(String ingestSecretArn) {
		this.ingestSecretArn = ingestSecretArn;
	}

	public String getChannelId() {
		return channelId;
	}

	public void setChannelId(String channelId) {
		this.channelId = channelId;
	}

	public String getOriginSlug() {
		return originSlug;
	}

	public void setOriginSlug(String originSlug) {
		this.originSlug = originSlug;
	}

	public Instant getStartedAt() {
		return startedAt;
	}

	public void setStartedAt(Instant startedAt) {
		this.startedAt = startedAt;
	}

	public Instant getEndedAt() {
		return endedAt;
	}

	public void setEndedAt(Instant endedAt) {
		this.endedAt = endedAt;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

}
