package tech.mmarca.openwatch.wear

/**
 * Where the heart rate or the night HRV windows come from, as the wearer or the phone chose
 * it: the raw Android sensors, or the Samsung Health Platform. The watch app's settings call
 * it `WearSettings.Source` and keep the entry's name in their preferences file, so the names
 * are final. [SAMSUNG_PPG], the platform's green PPG analysed on the watch, is a choice for
 * the HRV windows only.
 */
enum class MeasurementSource { RAW_SENSOR, SAMSUNG_PLATFORM, SAMSUNG_PPG }
