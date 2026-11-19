package app.surrealar.ui.openinar

import android.content.res.AssetManager
import com.google.android.filament.EntityManager
import com.google.android.filament.Engine
import com.google.android.filament.IndexBuffer
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.Scene
import com.google.android.filament.VertexBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Filament renderable for the depth-occlusion mesh ([OcclusionFrame]).
 *
 * Drawn with colour writes OFF and depth writes ON, at the highest priority (first), so it fills
 * the depth buffer with the real-world surface and any model fragment behind it fails the depth
 * test. It produces no visible pixels, so the camera feed still shows through.
 *
 * Reuses the app's existing `unlit.filamat` (which requires a vertex colour stream — supplied as a
 * constant), so no new compiled material is needed. Main thread only.
 */
internal class FilamentDepthOccluder(private val engine: Engine, assets: AssetManager) {

    private val material: Material
    private val instance: MaterialInstance
    private val vertexBuffer: VertexBuffer
    private val indexBuffer: IndexBuffer
    private val entity: Int = EntityManager.get().create()
    private var inScene = false
    private var uploaded: OcclusionFrame? = null

    init {
        val bytes = assets.open("unlit.filamat").use { it.readBytes() }
        val payload = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        payload.put(bytes).flip()
        material = Material.Builder().payload(payload, payload.remaining()).build(engine)
        instance = material.createInstance().apply {
            setColorWrite(false)
            setDepthWrite(true)
            setDepthCulling(true)
            setCullingMode(Material.CullingMode.NONE)
        }

        val n = DepthOccluderMesh.VERTEX_COUNT
        vertexBuffer = VertexBuffer.Builder()
            .bufferCount(2)
            .vertexCount(n)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 12)
            .attribute(VertexBuffer.VertexAttribute.COLOR, 1, VertexBuffer.AttributeType.UBYTE4, 0, 4)
            .normalized(VertexBuffer.VertexAttribute.COLOR)
            .build(engine)
        // Constant colour stream required by unlit.mat; never visible (colour writes are off).
        vertexBuffer.setBufferAt(engine, 1, ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()))
        vertexBuffer.setBufferAt(engine, 0, ByteBuffer.allocateDirect(n * 12).order(ByteOrder.nativeOrder()))

        indexBuffer = IndexBuffer.Builder()
            .indexCount(DepthOccluderMesh.MAX_INDEX_COUNT)
            .bufferType(IndexBuffer.Builder.IndexType.USHORT)
            .build(engine)
        indexBuffer.setBuffer(engine,
            ByteBuffer.allocateDirect(DepthOccluderMesh.MAX_INDEX_COUNT * 2).order(ByteOrder.nativeOrder()))

        RenderableManager.Builder(1)
            .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vertexBuffer, indexBuffer,
                0, DepthOccluderMesh.MAX_INDEX_COUNT)
            .material(0, instance)
            .culling(false)          // spans the whole view; never frustum-cull it
            .castShadows(false)
            .receiveShadows(false)
            .priority(0)             // draw before models (default priority 4)
            .build(engine, entity)
    }

    /**
     * Shows [frame] (uploading it if new) or, when null / empty, removes the occluder from [scene].
     */
    fun update(scene: Scene, frame: OcclusionFrame?) {
        if (frame == null || frame.indexCount == 0) {
            if (inScene) { scene.remove(entity); inScene = false }
            if (frame == null) uploaded = null
            return
        }
        if (frame !== uploaded) {
            // Fresh direct buffers each upload: Filament reads them asynchronously, so a buffer must
            // not be reused while a previous upload may still be pending.
            val pos = ByteBuffer.allocateDirect(frame.positions.size * 4).order(ByteOrder.nativeOrder())
                .asFloatBuffer()
            pos.put(frame.positions).flip()
            vertexBuffer.setBufferAt(engine, 0, pos)

            val idx = ByteBuffer.allocateDirect(frame.indexCount * 2).order(ByteOrder.nativeOrder())
                .asShortBuffer()
            idx.put(frame.indices, 0, frame.indexCount).flip()
            indexBuffer.setBuffer(engine, idx)

            val rm = engine.renderableManager
            rm.setGeometryAt(rm.getInstance(entity), 0, RenderableManager.PrimitiveType.TRIANGLES,
                vertexBuffer, indexBuffer, 0, frame.indexCount)
            uploaded = frame
        }
        if (!inScene) { scene.addEntity(entity); inScene = true }
    }

    /** Releases all Filament objects. Call before the scene / engine are destroyed. */
    fun destroy(scene: Scene) {
        runCatching { scene.remove(entity) }
        runCatching { engine.destroyEntity(entity) }
        runCatching { EntityManager.get().destroy(entity) }
        runCatching { engine.destroyIndexBuffer(indexBuffer) }
        runCatching { engine.destroyVertexBuffer(vertexBuffer) }
        runCatching { engine.destroyMaterialInstance(instance) }
        runCatching { engine.destroyMaterial(material) }
    }
}
