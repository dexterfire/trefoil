package com.tajchert.trefoil.security

import com.tajchert.trefoil.core.session.CorruptComposerDraftsException
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ComposerDraftFileStoreTest {
    private val context = RuntimeEnvironment.getApplication()
    private val base = File(context.noBackupFilesDir, "composer-drafts.v1")
    private val backup = File(context.noBackupFilesDir, "composer-drafts.v1.bak")

    @Test fun missingFileReadsAsNothingStored() = runBlocking {
        base.delete(); backup.delete()
        assertNull(ComposerDraftFileStore(context).load())
    }

    @Test fun backupOfAnInterruptedWriteIsRead() = runBlocking {
        base.delete(); backup.writeBytes(byteArrayOf(9, 9, 9))
        // The backup is restored and read (no Keystore needed to reject its malformed envelope as corrupt).
        val failure = runCatching { ComposerDraftFileStore(context).load() }.exceptionOrNull()
        assertTrue("$failure", failure is CorruptComposerDraftsException)
        assertTrue(base.exists())
    }
}
