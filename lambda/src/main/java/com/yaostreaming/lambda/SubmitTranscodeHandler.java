package com.yaostreaming.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.S3Event;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import software.amazon.awssdk.services.mediaconvert.MediaConvertClient;
import software.amazon.awssdk.services.mediaconvert.model.CreateJobRequest;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

/**
 * S3-triggered (raw bucket, {@code uploads/} prefix, {@code ObjectCreated:*})
 * — reads {@code videoId} straight out of the object key
 * ({@code uploads/{videoId}/{filename}}, see {@code VideoUploadService}), no
 * DB access needed at all.
 */
public class SubmitTranscodeHandler implements RequestHandler<S3Event, Void> {

	private static final Pattern VIDEO_ID_PATTERN = Pattern.compile("^uploads/(\\d+)/");

	private final S3Client s3Client;
	private final MediaConvertClient mediaConvertClient;
	private final String processedBucket;
	private final String mediaConvertRoleArn;
	private final String mediaConvertQueueArn;

	public SubmitTranscodeHandler() {
		// No account-specific endpoint dance needed: MediaConvert's
		// DescribeEndpoints (the traditional way to obtain one) is deprecated -
		// "account specific endpoints are no longer required" - so the plain
		// regional client is enough.
		this(S3Client.create(), MediaConvertClient.create(),
				System.getenv("PROCESSED_BUCKET"),
				System.getenv("MEDIACONVERT_ROLE_ARN"),
				System.getenv("MEDIACONVERT_QUEUE_ARN"));
	}

	SubmitTranscodeHandler(S3Client s3Client, MediaConvertClient mediaConvertClient, String processedBucket,
			String mediaConvertRoleArn, String mediaConvertQueueArn) {
		this.s3Client = s3Client;
		this.mediaConvertClient = mediaConvertClient;
		this.processedBucket = processedBucket;
		this.mediaConvertRoleArn = mediaConvertRoleArn;
		this.mediaConvertQueueArn = mediaConvertQueueArn;
	}

	@Override
	public Void handleRequest(S3Event event, Context context) {
		LambdaLogger logger = context.getLogger();
		for (S3Event.S3EventNotificationRecord record : event.getRecords()) {
			String rawBucket = record.getS3().getBucket().getName();
			String sourceKey = record.getS3().getObject().getKey();
			processOne(rawBucket, sourceKey, logger);
		}
		return null;
	}

	private void processOne(String rawBucket, String sourceKey, LambdaLogger logger) {
		Long videoId = parseVideoId(sourceKey);
		if (videoId == null) {
			logger.log("Skipping upload object with no parseable videoId: " + sourceKey);
			return;
		}

		String outputKey = "videos/" + videoId + "/master.m3u8";
		if (processedObjectExists(outputKey)) {
			// S3 event delivery is at-least-once - a duplicate delivery of the
			// same upload would otherwise submit (and get billed for) a second
			// MediaConvert job for work that's already done or in flight.
			logger.log("Output already exists for video " + videoId + " (" + outputKey + "), skipping duplicate submit");
			return;
		}

		String fileInput = "s3://" + rawBucket + "/" + sourceKey;
		String destination = "s3://" + processedBucket + "/videos/" + videoId + "/master";

		mediaConvertClient.createJob(CreateJobRequest.builder()
				.role(mediaConvertRoleArn)
				.queue(mediaConvertQueueArn)
				.settings(MediaConvertJobSettings.forVideo(fileInput, destination))
				.userMetadata(Map.of("videoId", videoId.toString()))
				.build());
		logger.log("Submitted MediaConvert job for video " + videoId);
	}

	private boolean processedObjectExists(String key) {
		try {
			s3Client.headObject(HeadObjectRequest.builder().bucket(processedBucket).key(key).build());
			return true;
		}
		catch (NoSuchKeyException ex) {
			return false;
		}
	}

	static Long parseVideoId(String sourceKey) {
		Matcher matcher = VIDEO_ID_PATTERN.matcher(sourceKey);
		return matcher.find() ? Long.valueOf(matcher.group(1)) : null;
	}

}
