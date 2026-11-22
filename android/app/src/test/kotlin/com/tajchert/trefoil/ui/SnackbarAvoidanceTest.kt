package com.tajchert.trefoil.ui

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Test

class SnackbarAvoidanceTest {
    private val fab = Rect(left = 900f, top = 1700f, right = 1040f, bottom = 1840f)

    @Test fun noSnackbarLeavesTheFabInPlace() {
        assertEquals(0f, snackbarLift(fab, null, 8f))
        assertEquals(0f, snackbarLift(fab, Rect(0f, 1880f, 1080f, 1880f), 8f))
    }

    @Test fun fullWidthSnackbarAtTheBottomLiftsTheFabClearOfIt() {
        // Snackbar spans the width and sits 16 px below the FAB's bottom, 150 px tall.
        val lift = snackbarLift(fab, Rect(0f, 1706f, 1080f, 1856f), 8f)
        assertEquals(142f, lift)
    }

    @Test fun snackbarBesideTheFabDoesNotMoveIt() {
        // Wide layouts center a narrower snackbar that does not reach the FAB's column.
        assertEquals(0f, snackbarLift(fab, Rect(200f, 1706f, 880f, 1856f), 8f))
    }

    @Test fun snackbarWellBelowTheFabDoesNotMoveIt() {
        assertEquals(0f, snackbarLift(fab, Rect(0f, 1900f, 1080f, 2000f), 8f))
    }
}
