package com.zz.filemanager.core.storage

import com.zz.filemanager.core.model.BrowserLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserHistoryTest {
    @Test fun backAndForwardAreIndependentOfParentNavigation() {
        val history = BrowserHistory()
        val root = location("root")
        val a = location("a")
        val b = location("b")
        history.recordNavigation(root, a)
        history.recordNavigation(a, b)
        assertTrue(history.canGoBack)
        assertEquals(a, history.back(b))
        assertTrue(history.canGoForward)
        assertEquals(b, history.forward(a))
    }

    @Test fun newNavigationClearsForwardStack() {
        val history = BrowserHistory()
        val root = location("root")
        val a = location("a")
        val b = location("b")
        val c = location("c")
        history.recordNavigation(root, a)
        history.recordNavigation(a, b)
        assertEquals(a, history.back(b))
        history.recordNavigation(a, c)
        assertFalse(history.canGoForward)
    }

    private fun location(id: String) = BrowserLocation("test", id, id, id, "root", "test")
}
