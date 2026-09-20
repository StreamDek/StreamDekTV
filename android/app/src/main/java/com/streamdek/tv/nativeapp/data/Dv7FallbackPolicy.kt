package com.streamdek.tv.nativeapp.data

/** Shared rules in Mobile and TV. Never hand protected content to the non-DRM mpv path. */
internal fun shouldUseDv7Fallback(
  enabled: Boolean,
  profile7: Boolean,
  nativeSupported: Boolean,
  decoderFailed: Boolean,
  protectedContent: Boolean,
): Boolean = enabled && profile7 && !protectedContent && (!nativeSupported || decoderFailed)
