package com.tajchert.trefoil

import android.content.Context
import androidx.annotation.StringRes
import androidx.test.core.app.ApplicationProvider

/** Strings resolve with the test device locale (en-US); tests assert against the same resources the UI reads. */
internal fun testString(@StringRes id: Int, vararg args: Any): String =
    ApplicationProvider.getApplicationContext<Context>().getString(id, *args)
