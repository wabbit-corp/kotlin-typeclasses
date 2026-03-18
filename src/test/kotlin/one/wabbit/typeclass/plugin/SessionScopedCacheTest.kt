package one.wabbit.typeclass.plugin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class SessionScopedCacheTest {
    @Test
    fun cachesValuesPerKeyInsteadOfGlobally() {
        val cache = SessionScopedCache<Any, String>()
        val firstKey = Any()
        val secondKey = Any()
        var computations = 0

        val first = cache.getOrPut(firstKey) { "first-${++computations}" }
        val second = cache.getOrPut(secondKey) { "second-${++computations}" }
        val firstAgain = cache.getOrPut(firstKey) { "first-${++computations}" }

        assertEquals("first-1", first)
        assertEquals("second-2", second)
        assertSame(first, firstAgain)
        assertEquals(2, computations)
    }
}
