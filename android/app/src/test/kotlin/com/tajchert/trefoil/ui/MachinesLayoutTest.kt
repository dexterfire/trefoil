package com.tajchert.trefoil.ui

import com.tajchert.trefoil.core.model.SavedEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MachinesLayoutTest {
    private fun machine(id: String) = SavedEnvironment(id, id, "https://$id.example.test", "fixture")
    private val saved = listOf(machine("a"), machine("b"), machine("c"))

    @Test fun phoneWidthsKeepTodaysScreenWhateverIsSaved() {
        assertEquals(MachinesLayout.Phone, machinesLayout(390f, hasSaved = true))
        assertEquals(MachinesLayout.Phone, machinesLayout(599f, hasSaved = false))
    }

    @Test fun firstRunUsesACardFromSixHundred() {
        assertEquals(MachinesLayout.FirstRunCard, machinesLayout(600f, hasSaved = false))
        assertEquals(MachinesLayout.FirstRunCard, machinesLayout(1280f, hasSaved = false))
    }

    @Test fun savedMachinesUseAColumnUntilEightHundredFortyThenListDetail() {
        assertEquals(MachinesLayout.Column, machinesLayout(600f, hasSaved = true))
        assertEquals(MachinesLayout.Column, machinesLayout(839f, hasSaved = true))
        assertEquals(MachinesLayout.ListDetail, machinesLayout(840f, hasSaved = true))
        assertEquals(MachinesLayout.ListDetail, machinesLayout(1840f, hasSaved = true))
    }

    @Test fun firstRunCardNarrowsBelowSevenHundredTwenty() {
        assertEquals(520f, firstRunCardMaxWidth(673f), 0f)
        assertEquals(520f, firstRunCardMaxWidth(719f), 0f)
        assertEquals(560f, firstRunCardMaxWidth(720f), 0f)
    }

    @Test fun selectionPrefersTheChosenThenTheConnectedThenTheFirstMachine() {
        assertEquals("b", selectedMachine(saved, chosenId = "b", connectedId = "c")?.id)
        assertEquals("c", selectedMachine(saved, chosenId = null, connectedId = "c")?.id)
        assertEquals("a", selectedMachine(saved, chosenId = null, connectedId = null)?.id)
    }

    @Test fun aForgottenOrUnknownIdFallsBackCleanly() {
        assertEquals("c", selectedMachine(saved, chosenId = "gone", connectedId = "c")?.id)
        assertEquals("a", selectedMachine(saved, chosenId = "gone", connectedId = "also-gone")?.id)
        assertNull(selectedMachine(emptyList(), chosenId = "a", connectedId = "a"))
    }
}
