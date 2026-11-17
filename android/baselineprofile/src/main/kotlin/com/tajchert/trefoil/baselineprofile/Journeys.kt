package com.tajchert.trefoil.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until

internal const val TARGET_PACKAGE = "com.tajchert.trefoil"
private const val TIMEOUT_MS = 5_000L

/** Opens "Pair with link" on the machines screen, waits for the dialog, then dismisses it. */
internal fun MacrobenchmarkScope.openPairDialogAndBack() {
    val pair = device.wait(Until.findObject(By.desc("Pair with link")), TIMEOUT_MS) ?: return
    pair.click()
    device.wait(Until.hasObject(By.text("Pair an environment")), TIMEOUT_MS)
    device.pressBack()
    device.waitForIdle()
}
