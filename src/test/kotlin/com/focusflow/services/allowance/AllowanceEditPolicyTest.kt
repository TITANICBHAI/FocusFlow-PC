package com.focusflow.services.allowance

import com.focusflow.data.models.DailyAllowance
import com.focusflow.services.allowance.normalizeManualProcessName
import kotlin.test.Test
import kotlin.test.assertEquals

class AllowanceEditPolicyTest {

    @Test
    fun classifiesAddsTighteningLooseningUnchangedAndDeletion() {
        val existing = DailyAllowance("chat.exe", "Chat", 30)
        val cases = listOf(
            Triple(null, existing, AllowanceEditChange.Add),
            Triple(existing, existing.copy(allowanceMinutes = 15), AllowanceEditChange.Tighten),
            Triple(existing, existing.copy(allowanceMinutes = 60), AllowanceEditChange.Loosen),
            Triple(existing, existing.copy(displayName = "Chat App"), AllowanceEditChange.Unchanged),
            Triple(existing, null, AllowanceEditChange.Delete)
        )

        cases.forEach { (old, new, expected) ->
            assertEquals(expected, AllowanceEditPolicy.classify(old, new), "old=$old, new=$new")
        }
    }

    @Test
    fun onlyLooseningAndDeletionRequireTheGlobalPin() {
        val existing = DailyAllowance("chat.exe", "Chat", 30)

        assertEquals(false, AllowanceEditPolicy.requiresPin(AllowanceEditChange.Add))
        assertEquals(false, AllowanceEditPolicy.requiresPin(AllowanceEditChange.Tighten))
        assertEquals(true, AllowanceEditPolicy.requiresPin(AllowanceEditChange.Loosen))
        assertEquals(false, AllowanceEditPolicy.requiresPin(AllowanceEditChange.Unchanged))
        assertEquals(true, AllowanceEditPolicy.requiresPin(AllowanceEditPolicy.classify(existing, null)))
    }

    @Test
    fun pinPromptRequiresConfiguredGlobalPinAndProtectedChange() {
        val existing = DailyAllowance("chat.exe", "Chat", 30)

        assertEquals(false, AllowanceEditPolicy.shouldPromptForPin(null, existing, globalPinSet = true))
        assertEquals(false, AllowanceEditPolicy.shouldPromptForPin(
            existing, existing.copy(allowanceMinutes = 15), globalPinSet = true
        ))
        assertEquals(true, AllowanceEditPolicy.shouldPromptForPin(
            existing, existing.copy(allowanceMinutes = 60), globalPinSet = true
        ))
        assertEquals(true, AllowanceEditPolicy.shouldPromptForPin(existing, null, globalPinSet = true))
        assertEquals(false, AllowanceEditPolicy.shouldPromptForPin(existing, null, globalPinSet = false))
    }

    @Test
    fun editingIsDisabledUntilDatabaseAndPinStateAreReadyAndNoWriteIsRunning() {
        assertEquals(false, AllowanceEditPolicy.canMutate(false, true, false))
        assertEquals(false, AllowanceEditPolicy.canMutate(true, false, false))
        assertEquals(false, AllowanceEditPolicy.canMutate(true, true, true))
        assertEquals(true, AllowanceEditPolicy.canMutate(true, true, false))
    }

    @Test
    fun processNameCaseAndWhitespaceDoNotTurnAnEditIntoANewAllowance() {
        val old = DailyAllowance(" Discord.EXE ", "Discord", 30)
        val edited = DailyAllowance("discord.exe", "Discord", 45)

        assertEquals(AllowanceEditChange.Loosen, AllowanceEditPolicy.classify(old, edited))
    }

    @Test
    fun manualProcessNameGetsExeSuffixOnlyOnWindows() {
        assertEquals("discord.exe", normalizeManualProcessName(" Discord ", isWindows = true))
        assertEquals("discord.exe", normalizeManualProcessName("Discord.EXE", isWindows = true))
        assertEquals("discord", normalizeManualProcessName(" Discord ", isWindows = false))
    }
}
