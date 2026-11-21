package io.github.pennstatecs.geokit

import org.junit.Assert.assertNull
import org.junit.Test

/** Proves the module builds and runs its tests on the plain JVM (no Android on the classpath). */
class ModuleSmokeTest {
    @Test fun `no Android classes on the classpath`() {
        assertNull(runCatching { Class.forName("android.os.Build") }.getOrNull())
    }
}
