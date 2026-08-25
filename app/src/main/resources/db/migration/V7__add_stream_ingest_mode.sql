-- Which of the two coexisting go-live mechanisms a stream uses. Every
-- existing row is an OBS/RTMP stream, so DEFAULT 'RTMP' backfills them
-- correctly in the same statement; new rows always set it explicitly from
-- here on (the default just satisfies the NOT NULL constraint for rows
-- that predate this column, not an ongoing app-level default).
ALTER TABLE streams
    ADD COLUMN ingest_mode VARCHAR(20) NOT NULL DEFAULT 'RTMP';

ALTER TABLE streams
    ADD CONSTRAINT chk_streams_ingest_mode CHECK (ingest_mode IN ('RTMP', 'WEBRTC'));

-- The IVS stream key's own ARN - null for RTMP streams. See Stream.java's
-- own field javadoc for why it exists and why it's never nulled once set.
ALTER TABLE streams
    ADD COLUMN ingest_secret_arn VARCHAR(255) NULL;
