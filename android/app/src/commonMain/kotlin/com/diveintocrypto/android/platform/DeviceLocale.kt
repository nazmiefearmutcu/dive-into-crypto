package com.diveintocrypto.android.platform

/**
 * Primary language subtag of the device locale, lower-case ("tr", "en", …).
 *   - Android → java.util.Locale.getDefault().language
 * Used once, to pick the default UI language when the user has not chosen one.
 */
expect fun deviceLanguageTag(): String
