CREATE TABLE streams (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    description VARCHAR(2000) NULL,
    status VARCHAR(20) NOT NULL,
    -- The currently/last-bound MediaLive channel ARN. Not FK'd to a channels
    -- table (the pool is fixed Terraform config, not app data) and
    -- deliberately not nulled on ENDED/FAILED so it stays useful as audit
    -- history of which pool slot a stream used.
    channel_id VARCHAR(255) NULL,
    -- Denormalized CloudFront path-prefix (e.g. "pool-0"), set at reservation
    -- time so the watch page needs zero runtime lookup - same idea as
    -- videos/{id}/master.m3u8, but keyed by pool slot since only 5 fixed
    -- MediaPackage endpoints exist and get reused across many streams.
    origin_slug VARCHAR(50) NULL,
    started_at TIMESTAMP NULL,
    ended_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_streams_user FOREIGN KEY (user_id) REFERENCES users (id)
        ON DELETE RESTRICT
        ON UPDATE RESTRICT,
    CONSTRAINT chk_streams_status CHECK (status IN ('PENDING', 'STARTING', 'LIVE', 'ENDING', 'ENDED', 'FAILED'))
);
