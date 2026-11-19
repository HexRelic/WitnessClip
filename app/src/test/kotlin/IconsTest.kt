// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 HexRelic

import androidx.compose.ui.graphics.vector.PathParser
import io.github.hexrelic.witnessclip.ui.Icons
import org.junit.Assert.assertTrue
import org.junit.Test

class IconsTest {
    /** Every icon path must parse; a typo would otherwise crash the first screen that draws it. */
    @Test fun allIconPathsParse() {
        val fields = Icons::class.java.declaredFields.filter { it.type == String::class.java }
        assertTrue(fields.size > 20)
        for (f in fields) {
            f.isAccessible = true
            val nodes = PathParser().parsePathString(f.get(null) as String).toNodes()
            assertTrue(f.name, nodes.size >= 2)
        }
    }
}
