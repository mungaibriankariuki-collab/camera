package com.brian.camera.camera

/** Shooting modes. [main] ones sit on the mode bar; the rest live under MORE. */
enum class Mode(val label: String, val video: Boolean, val main: Boolean) {
    PORTRAIT("Portrait", false, true),
    PHOTO("Photo", false, true),
    VIDEO("Video", true, true),
    NIGHT("Night", false, true),
    PRO("Pro", false, false),
    SINGLE_TAKE("Single take", true, false),
    FOOD("Food", false, false),
    PANORAMA("Panorama", false, false),
    HYPERLAPSE("Hyperlapse", true, false),
    SLOW_MO("Slow motion", true, false),
    DUAL("Director's view", true, false),
    DOC("Document scan", false, false),
    LENS("Lens", false, false);

    companion object {
        fun of(name: String) = entries.firstOrNull { it.name == name } ?: PHOTO
    }
}
