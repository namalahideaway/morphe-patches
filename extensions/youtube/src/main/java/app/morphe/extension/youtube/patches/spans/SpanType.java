/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches.spans;

import androidx.annotation.NonNull;

enum SpanType {
    ABSOLUTE_SIZE("AbsoluteSizeSpan"),
    CLICKABLE("ClickableSpan"),
    CUSTOM_CHARACTER_STYLE("CustomCharacterStyle"),
    FOREGROUND_COLOR("ForegroundColorSpan"),
    IMAGE("ImageSpan"),
    LINE_HEIGHT("LineHeightSpan"),
    TYPEFACE("TypefaceSpan"),
    UNKNOWN("Unknown");

    @NonNull
    public final String type;

    SpanType(@NonNull String type) {
        this.type = type;
    }
}