package com.yaostreaming.api.live;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request to start a broadcast. No source file - unlike {@code UploadRequest}, there's nothing to presign yet. */
public record GoLiveRequest(
		@NotBlank @Size(max = 255) String title,

		@Size(max = 2000) String description) {
}
