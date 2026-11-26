package app.surrealar.data.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ModelOrphanSweeperTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val now = 1_800_000_000_000L
    private val old = now - ModelOrphanSweeper.MIN_AGE_MS - 60_000L
    private val fresh = now - 60_000L

    private fun File.age(t: Long): File = apply { setLastModified(t) }

    @Test
    fun sweep_deletesOnlyOldUnreferencedEntries() {
        val root = tmp.newFolder("files", "models")
        val kept = File(root, "kept.glb").apply { writeText("k") }.age(old)
        val orphan = File(root, "orphan.glb").apply { writeText("o") }.age(old)
        val young = File(root, "importing.glb").apply { writeText("y") }.age(fresh)

        val keptBundle = File(root, "bridge").apply { mkdirs() }
        File(keptBundle, "bridge.gltf").apply { writeText("{}") }.age(old)
        File(keptBundle, "bridge.bin").apply { writeText("b") }.age(old)
        keptBundle.age(old)

        val orphanBundle = File(root, "tower").apply { mkdirs() }
        File(orphanBundle, "tower.gltf").apply { writeText("{}") }.age(old)
        orphanBundle.age(old)

        val removed = ModelOrphanSweeper.sweep(
            root,
            listOf(kept.absolutePath, File(keptBundle, "bridge.gltf").absolutePath, null, ""),
            now
        )

        assertEquals(setOf("orphan.glb", "tower"), removed.map { it.name }.toSet())
        assertTrue(kept.exists())
        assertTrue(young.exists())
        assertTrue(File(keptBundle, "bridge.bin").exists())
        assertFalse(orphan.exists())
        assertFalse(orphanBundle.exists())
    }

    @Test
    fun sweep_bundleWithRecentlyWrittenFile_isKept() {
        val root = tmp.newFolder("files", "models")
        val bundle = File(root, "copying").apply { mkdirs() }
        File(bundle, "a.gltf").apply { writeText("{}") }.age(old)
        File(bundle, "a.bin").apply { writeText("b") }.age(fresh) // still being copied
        bundle.age(old)

        assertTrue(ModelOrphanSweeper.sweep(root, emptyList(), now).isEmpty())
        assertTrue(bundle.exists())
    }

    @Test
    fun sweep_matchesPathsSavedUnderAnotherDataDir() {
        val root = tmp.newFolder("files", "models")
        val glb = File(root, "moved.glb").apply { writeText("m") }.age(old)

        val removed = ModelOrphanSweeper.sweep(
            root, listOf("/data/data/app.surrealar/files/models/moved.glb"), now
        )

        assertTrue(removed.isEmpty())
        assertTrue(glb.exists())
    }

    @Test
    fun sweep_missingRoot_isNoOp() {
        val root = File(tmp.root, "files/models")
        assertTrue(ModelOrphanSweeper.sweep(root, emptyList(), now).isEmpty())
    }

    @Test
    fun sweep_neverTouchesOutsideRoot() {
        val filesDir = tmp.newFolder("files")
        val root = File(filesDir, "models").apply { mkdirs() }
        val sibling = File(filesDir, "thumbnails.png").apply { writeText("t") }.age(old)

        ModelOrphanSweeper.sweep(root, emptyList(), now)

        assertTrue(sibling.exists())
        assertTrue(root.exists())
    }

    /** Stabilization S2 item 11: a staged import (copied, no Room row yet) inside the grace period is kept. */
    @Test
    fun sweep_interruptedImportWithinGrace_isKept_pastGrace_isSwept() {
        val root = tmp.newFolder("files", "models")
        val staged = File(root, "staged.glb").apply { writeText("s") }
        val stagedBundle = File(root, "stagedBundle").apply { mkdirs() }
        File(stagedBundle, "m.gltf").apply { writeText("{}") }
        // Just inside the grace period (the copy finished MIN_AGE_MS - 1 s ago, dialog still open).
        val justInside = now - ModelOrphanSweeper.MIN_AGE_MS + 1_000L
        staged.age(justInside); File(stagedBundle, "m.gltf").age(justInside); stagedBundle.age(justInside)

        assertTrue(ModelOrphanSweeper.sweep(root, emptyList(), now).isEmpty())
        assertTrue(staged.exists() && stagedBundle.exists())

        // A later start after the grace period: the import can no longer be committed, so it is a genuine orphan.
        val later = now + 2_000L
        assertEquals(setOf("staged.glb", "stagedBundle"), ModelOrphanSweeper.sweep(root, emptyList(), later).map { it.name }.toSet())
    }

    @Test
    fun sweep_neverFollowsALinkOutsideTheModelsFolder() {
        val filesDir = tmp.newFolder("files")
        val root = File(filesDir, "models").apply { mkdirs() }
        val outside = tmp.newFolder("outside")
        val precious = File(outside, "precious.txt").apply { writeText("p") }.age(old)
        val link = File(root, "link")
        val created = runCatching { java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath()) }.isSuccess
        org.junit.Assume.assumeTrue("symbolic links not available on this machine", created)

        val removed = ModelOrphanSweeper.sweep(root, emptyList(), now + ModelOrphanSweeper.MIN_AGE_MS * 10)

        assertTrue(removed.isEmpty())
        assertTrue(precious.exists())
    }
}
