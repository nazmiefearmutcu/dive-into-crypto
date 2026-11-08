package com.diveintocrypto.android.platform

import java.util.Locale

actual fun deviceLanguageTag(): String = Locale.getDefault().language.lowercase(Locale.ROOT)
