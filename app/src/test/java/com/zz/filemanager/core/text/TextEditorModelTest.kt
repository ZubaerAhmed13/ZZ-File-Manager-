package com.zz.filemanager.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextEditorModelTest {
    @Test
    fun undoRedoTracksStagedEditsWithoutMutatingSource() {
        val history = TextUndoRedoBuffer("one")
        history.update("two")
        history.update("three")
        assertTrue(history.canUndo)
        assertEquals("two", history.undo())
        assertEquals("one", history.undo())
        assertFalse(history.canUndo)
        assertTrue(history.canRedo)
        assertEquals("two", history.redo())
        assertEquals("three", history.redo())
        assertFalse(history.canRedo)
    }

    @Test
    fun newEditClearsRedoBranch() {
        val history = TextUndoRedoBuffer("a")
        history.update("b")
        history.update("c")
        history.undo()
        assertTrue(history.canRedo)
        history.update("b2")
        assertFalse(history.canRedo)
        assertEquals("b2", history.text)
    }

    @Test
    fun historyIsBounded() {
        val history = TextUndoRedoBuffer("0", maxSnapshots = 3)
        (1..8).forEach { history.update(it.toString()) }
        assertEquals("7", history.undo())
        assertEquals("6", history.undo())
        assertEquals("5", history.undo())
        assertFalse(history.canUndo)
    }

    @Test
    fun lineEndingSequencesAreExplicit() {
        assertEquals("\n", LineEnding.LF.sequence)
        assertEquals("\r\n", LineEnding.CRLF.sequence)
    }

    @Test
    fun encodingLabelsRemainUnambiguous() {
        assertEquals("UTF-8", TextEncoding.UTF8.displayName)
        assertEquals("UTF-8 BOM", TextEncoding.UTF8_BOM.displayName)
        assertEquals("UTF-16 LE", TextEncoding.UTF16_LE.displayName)
        assertEquals("UTF-16 BE", TextEncoding.UTF16_BE.displayName)
    }
}
