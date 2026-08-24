package com.yaostreaming.lambda;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.events.S3Event;
import com.amazonaws.services.lambda.runtime.events.models.s3.S3EventNotification;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.mediaconvert.MediaConvertClient;
import software.amazon.awssdk.services.mediaconvert.model.CreateJobRequest;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

@ExtendWith(MockitoExtension.class)
class SubmitTranscodeHandlerTest {

	@Mock
	private S3Client s3Client;

	@Mock
	private MediaConvertClient mediaConvertClient;

	@Mock
	private Context context;

	private SubmitTranscodeHandler handler;

	@BeforeEach
	void setUp() {
		handler = new SubmitTranscodeHandler(s3Client, mediaConvertClient, "processed-bucket",
				"arn:aws:iam::123456789012:role/mediaconvert-execution", "arn:aws:mediaconvert:::queues/Default");
	}

	/** Only stubbed by tests that actually call handleRequest(); Mockito's
	 * strict stubs flag it as unnecessary on the two parseVideoId tests below,
	 * which never touch context at all. */
	private void stubLogger() {
		when(context.getLogger()).thenReturn(new NoOpLogger());
	}

	@Test
	void parsesVideoIdFromTheUploadKeyPrefix() {
		assertThat(SubmitTranscodeHandler.parseVideoId("uploads/42/clip.mp4")).isEqualTo(42L);
	}

	@Test
	void returnsNullWhenTheKeyHasNoParseableVideoId() {
		assertThat(SubmitTranscodeHandler.parseVideoId("something-else/clip.mp4")).isNull();
	}

	@Test
	void submitsAJobWhenNoOutputExistsYet() {
		stubLogger();
		when(s3Client.headObject(HeadObjectRequest.builder()
				.bucket("processed-bucket").key("videos/42/master.m3u8").build()))
				.thenThrow(NoSuchKeyException.builder().build());

		handler.handleRequest(s3Event("raw-bucket", "uploads/42/clip.mp4"), context);

		ArgumentCaptor<CreateJobRequest> captor = ArgumentCaptor.forClass(CreateJobRequest.class);
		verify(mediaConvertClient).createJob(captor.capture());
		CreateJobRequest request = captor.getValue();
		assertThat(request.role()).isEqualTo("arn:aws:iam::123456789012:role/mediaconvert-execution");
		assertThat(request.queue()).isEqualTo("arn:aws:mediaconvert:::queues/Default");
		assertThat(request.userMetadata()).containsEntry("videoId", "42");
		assertThat(request.settings().inputs().get(0).fileInput()).isEqualTo("s3://raw-bucket/uploads/42/clip.mp4");
		assertThat(request.settings().outputGroups().get(0).outputGroupSettings().hlsGroupSettings().destination())
				.isEqualTo("s3://processed-bucket/videos/42/master");
	}

	@Test
	void skipsSubmittingWhenTheOutputAlreadyExists() {
		stubLogger();
		when(s3Client.headObject(HeadObjectRequest.builder()
				.bucket("processed-bucket").key("videos/42/master.m3u8").build()))
				.thenReturn(HeadObjectResponse.builder().build());

		handler.handleRequest(s3Event("raw-bucket", "uploads/42/clip.mp4"), context);

		verify(mediaConvertClient, never()).createJob(any(CreateJobRequest.class));
	}

	@Test
	void skipsRecordsWithNoParseableVideoId() {
		stubLogger();
		handler.handleRequest(s3Event("raw-bucket", "something-else/clip.mp4"), context);

		verify(mediaConvertClient, never()).createJob(any(CreateJobRequest.class));
	}

	/**
	 * These model classes (unlike the AWS SDK's own, e.g. S3Client's requests)
	 * are plain classes with only a full-args constructor - no builders - so
	 * irrelevant fields are passed as null/placeholder values here.
	 */
	private static S3Event s3Event(String bucket, String key) {
		S3EventNotification.S3BucketEntity bucketEntity =
				new S3EventNotification.S3BucketEntity(bucket, null, null);
		S3EventNotification.S3ObjectEntity objectEntity =
				new S3EventNotification.S3ObjectEntity(key, (Long) null, null, null, null);
		S3EventNotification.S3Entity s3Entity =
				new S3EventNotification.S3Entity(null, bucketEntity, objectEntity, null);
		S3EventNotification.S3EventNotificationRecord record = new S3EventNotification.S3EventNotificationRecord(
				null, null, null, null, null, null, null, s3Entity, null);
		return new S3Event(List.of(record));
	}

	/** Avoids a real CloudWatch dependency in unit tests. */
	private static final class NoOpLogger implements LambdaLogger {
		@Override
		public void log(String message) {
		}

		@Override
		public void log(byte[] message) {
		}
	}

}
