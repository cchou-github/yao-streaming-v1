package com.yaostreaming.lambda;

import java.util.List;
import java.util.Map;
import software.amazon.awssdk.services.mediaconvert.model.AacCodingMode;
import software.amazon.awssdk.services.mediaconvert.model.AacSettings;
import software.amazon.awssdk.services.mediaconvert.model.AudioCodec;
import software.amazon.awssdk.services.mediaconvert.model.AudioCodecSettings;
import software.amazon.awssdk.services.mediaconvert.model.AudioDescription;
import software.amazon.awssdk.services.mediaconvert.model.AudioSelector;
import software.amazon.awssdk.services.mediaconvert.model.AudioSelectorType;
import software.amazon.awssdk.services.mediaconvert.model.ContainerSettings;
import software.amazon.awssdk.services.mediaconvert.model.ContainerType;
import software.amazon.awssdk.services.mediaconvert.model.H264RateControlMode;
import software.amazon.awssdk.services.mediaconvert.model.H264Settings;
import software.amazon.awssdk.services.mediaconvert.model.HlsGroupSettings;
import software.amazon.awssdk.services.mediaconvert.model.Input;
import software.amazon.awssdk.services.mediaconvert.model.JobSettings;
import software.amazon.awssdk.services.mediaconvert.model.Output;
import software.amazon.awssdk.services.mediaconvert.model.OutputGroup;
import software.amazon.awssdk.services.mediaconvert.model.OutputGroupSettings;
import software.amazon.awssdk.services.mediaconvert.model.OutputGroupType;
import software.amazon.awssdk.services.mediaconvert.model.VideoCodec;
import software.amazon.awssdk.services.mediaconvert.model.VideoCodecSettings;
import software.amazon.awssdk.services.mediaconvert.model.VideoDescription;
import software.amazon.awssdk.services.mediaconvert.model.VideoSelector;

/**
 * Builds the one job shape this project needs: single-rendition H.264/AAC
 * HLS, matching {@code LocalFfmpegTranscodePipeline}'s local output exactly so
 * the two paths are interchangeable from the app's point of view. Built with
 * the SDK's builder API rather than a checked-in JSON template - the Java
 * MediaConvert client takes a modeled {@link JobSettings}, not raw JSON the
 * way the AWS CLI's {@code --cli-input-json} does, so "Terraform/a resource
 * file owns the encoding settings" isn't achievable the way the original
 * design sketch assumed. This class is the one place those settings live
 * instead - Terraform supplies nothing about encoding at all.
 *
 * <p>{@code destination} deliberately ends in {@code /master} rather than a
 * trailing slash: MediaConvert derives the HLS manifest's base filename from
 * the last path segment of {@code Destination}, appending {@code .m3u8}
 * itself. Ending in {@code /master} is what makes the output land at exactly
 * {@code videos/{videoId}/master.m3u8} - the same convention
 * {@code LocalFfmpegTranscodePipeline} already uses locally.
 */
final class MediaConvertJobSettings {

	private MediaConvertJobSettings() {
	}

	static JobSettings forVideo(String rawFileInput, String hlsDestination) {
		Input input = Input.builder()
				.fileInput(rawFileInput)
				.videoSelector(VideoSelector.builder().build())
				.audioSelectors(Map.of("Audio Selector 1",
						AudioSelector.builder().selectorType(AudioSelectorType.TRACK).build()))
				.build();

		Output output = Output.builder()
				// The SDK's Output.Builder defaults nameModifier to "" rather
				// than leaving it unset, even when this method is never called -
				// and MediaConvert rejects an empty string outright ("String
				// should be at least 1 characters long"). Confirmed by testing
				// both ways against real MediaConvert: omitting the call
				// entirely produces the identical validation error, so a real
				// value is required, not optional.
				.nameModifier("_master")
				.containerSettings(ContainerSettings.builder().container(ContainerType.M3_U8).build())
				.videoDescription(VideoDescription.builder()
						.codecSettings(VideoCodecSettings.builder()
								.codec(VideoCodec.H_264)
								.h264Settings(H264Settings.builder()
										.rateControlMode(H264RateControlMode.QVBR)
										// Required by MediaConvert even under QVBR (a quality-
										// targeted mode) - it still enforces this as a cap.
										// 5 Mbps is a reasonable ceiling for the SD-ish HLS
										// output this project produces.
										.maxBitrate(5000000)
										.build())
								.build())
						.build())
				.audioDescriptions(AudioDescription.builder()
						.codecSettings(AudioCodecSettings.builder()
								.codec(AudioCodec.AAC)
								.aacSettings(AacSettings.builder()
										.bitrate(96000)
										.codingMode(AacCodingMode.CODING_MODE_2_0)
										.sampleRate(48000)
										.build())
								.build())
						.build())
				.build();

		OutputGroup outputGroup = OutputGroup.builder()
				.name("HLS Group")
				.outputGroupSettings(OutputGroupSettings.builder()
						.type(OutputGroupType.HLS_GROUP_SETTINGS)
						.hlsGroupSettings(HlsGroupSettings.builder()
								.destination(hlsDestination)
								.segmentLength(4)
								.minSegmentLength(0)
								.build())
						.build())
				.outputs(output)
				.build();

		return JobSettings.builder()
				.inputs(List.of(input))
				.outputGroups(outputGroup)
				.build();
	}

}
