package com.soywiz.korag

import com.jtransc.FastMemory
import com.soywiz.korag.shader.Program
import com.soywiz.korag.shader.Uniform
import com.soywiz.korag.shader.VertexLayout
import com.soywiz.korim.bitmap.Bitmap
import com.soywiz.korim.bitmap.Bitmap32
import com.soywiz.korim.color.Colors
import com.soywiz.korio.async.Promise
import com.soywiz.korio.async.Signal
import com.soywiz.korio.async.async
import com.soywiz.korio.error.invalidOp
import com.soywiz.korio.util.Extra
import com.soywiz.korio.util.Pool
import com.soywiz.korma.geom.Size
import java.io.Closeable
import java.util.*

val agFactory by lazy {
	ServiceLoader.load(AGFactory::class.java).toList().filter(AGFactory::available).sortedBy(AGFactory::priority).firstOrNull()
		?: invalidOp("Can't find AGFactory implementation")
}

abstract class AGFactory {
	open val available: Boolean = true
	open val supportsNativeFrame: Boolean = false
	open val priority: Int = 4000

	abstract fun create(): AG
	open fun createFastWindow(title: String, width: Int, height: Int): AGWindow = invalidOp("Not supported")
}

interface AGContainer {
	val ag: AG

	val mouseX: Int
	val mouseY: Int
	val onMouseOver: Signal<Unit>
	val onMouseUp: Signal<Unit>
	val onMouseDown: Signal<Unit>

	//data class Resized(var width: Int, var height: Int) {
	//	fun setSize(width: Int, height: Int): Resized = this.apply {
	//		this.width = width
	//		this.height = height
	//	}
	//}

	fun repaint(): Unit
}

abstract class AGWindow : AGContainer {
	abstract override val ag: AG
}

abstract class AG : Extra by Extra.Mixin() {
	abstract val nativeComponent: Any
	open var backWidth: Int = 640
	open var backHeight: Int = 480

	open val maxTextureSize = Size(2048, 2048)

	open val pixelDensity: Double = 1.0

	private val onReadyDeferred = Promise.Deferred<AG>()
	protected fun ready() {
		onReadyDeferred.resolve(this)
	}

	val onReady = onReadyDeferred.promise
	val onRender = Signal<AG>()
	val onResized = Signal<Unit>()

	open fun repaint() {
	}

	open fun resized() {
		onResized(Unit)
	}

	open fun dispose() {
	}

	enum class BlendFactor {
		DESTINATION_ALPHA,
		DESTINATION_COLOR,
		ONE,
		ONE_MINUS_DESTINATION_ALPHA,
		ONE_MINUS_DESTINATION_COLOR,
		ONE_MINUS_SOURCE_ALPHA,
		ONE_MINUS_SOURCE_COLOR,
		SOURCE_ALPHA,
		SOURCE_COLOR,
		ZERO;
	}

	data class BlendFactors(val srcRGB: BlendFactor, val dstRGB: BlendFactor, val srcA: BlendFactor, val dstA: BlendFactor) {
		constructor(src: BlendFactor, dst: BlendFactor) : this(src, dst, src, dst)

		val disabled: Boolean get() = srcRGB == BlendFactor.ONE && dstRGB == BlendFactor.ZERO && srcA == BlendFactor.ONE && dstA == BlendFactor.ZERO
		val enabled: Boolean get() = !disabled

		companion object {
			// http://www.learnopengles.com/tag/additive-blending/
			val REPLACE = BlendFactors(BlendFactor.ONE, BlendFactor.ZERO, BlendFactor.ONE, BlendFactor.ZERO)
			val NORMAL = BlendFactors(BlendFactor.SOURCE_ALPHA, BlendFactor.ONE_MINUS_SOURCE_ALPHA, BlendFactor.ONE, BlendFactor.ONE_MINUS_SOURCE_ALPHA)
			val ADD = BlendFactors(BlendFactor.ONE, BlendFactor.ONE, BlendFactor.ONE, BlendFactor.ONE)
		}
	}

	interface BitmapSourceBase {
		val rgba: Boolean
		val width: Int
		val height: Int
	}

	class SyncBitmapSource(override val rgba: Boolean, override val width: Int, override val height: Int, val gen: () -> Bitmap?) : BitmapSourceBase {
		companion object {
			val NULL = SyncBitmapSource(true, 0, 0) { null }
		}
	}

	class AsyncBitmapSource(override val rgba: Boolean, override val width: Int, override val height: Int, val gen: suspend () -> Bitmap?) : BitmapSourceBase {
		companion object {
			val NULL = AsyncBitmapSource(true, 0, 0) { null }
		}
	}

	open class Texture : Closeable {
		var mipmaps = false
		var source: BitmapSourceBase = SyncBitmapSource.NULL
		private var uploaded: Boolean = false
		private var generating: Boolean = false
		private var generated: Boolean = false
		private var tempBitmap: Bitmap? = null
		var ready: Boolean = false; private set

		fun upload(bmp: Bitmap?, mipmaps: Boolean = false): Texture {
			return upload(if (bmp != null) SyncBitmapSource(rgba = bmp.bpp > 8, width = bmp.width, height = bmp.height) { bmp } else SyncBitmapSource.NULL, mipmaps)
		}

		fun upload(source: BitmapSourceBase, mipmaps: Boolean = false): Texture = this.apply {
			this.source = source
			uploadedSource()
			this.mipmaps = if (mipmaps) createMipmaps() else false
		}

		open protected fun uploadedSource() {
		}

		open fun bind() {
		}

		open fun unbind() {
		}

		fun bindEnsuring() {
			bind()
			val source = this.source
			if (!uploaded) {
				if (!generating) {
					generating = true
					when (source) {
						is SyncBitmapSource -> {
							tempBitmap = source.gen()
							generated = true
						}
						is AsyncBitmapSource -> {
							async {
								tempBitmap = source.gen()
								generated = true
							}
						}
					}
				}

				if (generated) {
					uploaded = true
					generating = false
					generated = false
					actualSyncUpload(source, tempBitmap)
					tempBitmap = null
					ready = true
				}
			}
		}

		open fun actualSyncUpload(source: BitmapSourceBase, bmp: Bitmap?) {
		}

		enum class Kind { RGBA, LUMINANCE }

		open protected fun createMipmaps() = false

		override fun close() {
		}
	}

	data class TextureUnit(
		var texture: AG.Texture? = null,
		var linear: Boolean = true
	)

	open class Buffer(val kind: Kind) : Closeable {
		enum class Kind { INDEX, VERTEX }

		var dirty = false
		protected var mem: FastMemory? = null
		protected var memOffset: Int = 0
		protected var memLength: Int = 0

		open fun afterSetMem() {
		}

		fun upload(data: ByteArray, offset: Int = 0, length: Int = data.size): Buffer {
			mem = FastMemory.alloc(length)
			FastMemory.copy(data, offset, mem!!, 0, length)
			memOffset = 0
			memLength = length
			dirty = true
			afterSetMem()
			return this
		}

		fun upload(data: FloatArray, offset: Int = 0, length: Int = data.size): Buffer {
			mem = FastMemory.alloc(length * 4)
			mem!!.setArrayFloat32(0, data, offset, length)
			memOffset = 0
			memLength = length * 4
			dirty = true
			afterSetMem()
			return this
		}

		fun upload(data: IntArray, offset: Int = 0, length: Int = data.size): Buffer {
			mem = FastMemory.alloc(length * 4)
			mem!!.setArrayInt32(0, data, offset, length)
			memOffset = 0
			memLength = length * 4
			dirty = true
			afterSetMem()
			return this
		}

		fun upload(data: ShortArray, offset: Int = 0, length: Int = data.size): Buffer {
			mem = FastMemory.alloc(length * 2)
			mem!!.setArrayInt16(0, data, offset, length)
			memOffset = 0
			memLength = length * 2
			dirty = true
			afterSetMem()
			return this
		}

		fun upload(data: FastMemory, offset: Int = 0, length: Int = data.length): Buffer {
			mem = data
			memOffset = offset
			memLength = length
			dirty = true
			afterSetMem()
			return this
		}

		override fun close() {
			mem = null
			memOffset = 0
			memLength = 0
			dirty = true
		}
	}

	enum class DrawType {
		TRIANGLES, TRIANGLE_STRIP
	}

	val dummyTexture by lazy { createTexture() }

	open fun createTexture(): Texture = Texture()
	fun createTexture(bmp: Bitmap, mipmaps: Boolean = false): Texture = createTexture().upload(bmp, mipmaps)
	open fun createBuffer(kind: Buffer.Kind) = Buffer(kind)
	fun createIndexBuffer() = createBuffer(Buffer.Kind.INDEX)
	fun createVertexBuffer() = createBuffer(Buffer.Kind.VERTEX)

	fun createIndexBuffer(data: ShortArray, offset: Int = 0, length: Int = data.size - offset) = createIndexBuffer().apply {
		upload(data, offset, length)
	}

	fun createIndexBuffer(data: FastMemory, offset: Int = 0, length: Int = data.length - offset) = createIndexBuffer().apply {
		upload(data, offset, length)
	}

	fun createVertexBuffer(data: FloatArray, offset: Int = 0, length: Int = data.size - offset) = createVertexBuffer().apply {
		upload(data, offset, length)
	}

	fun createVertexBuffer(data: FastMemory, offset: Int = 0, length: Int = data.length - offset) = createVertexBuffer().apply {
		upload(data, offset, length)
	}

	open fun draw(vertices: Buffer, indices: Buffer, program: Program, type: DrawType, vertexLayout: VertexLayout, vertexCount: Int, offset: Int = 0, blending: BlendFactors = BlendFactors.NORMAL, uniforms: Map<Uniform, Any> = mapOf()) {
		//VertexFormat()
		//	.add("hello", VertexFormat.Element.Type.Byte4)
	}

	fun draw(vertices: Buffer, program: Program, type: DrawType, vertexLayout: VertexLayout, vertexCount: Int, offset: Int = 0, blending: BlendFactors = BlendFactors.NORMAL, uniforms: Map<Uniform, Any> = mapOf()) {
		createIndexBuffer((0 until vertexCount).map(Int::toShort).toShortArray()).use { indices ->
			draw(vertices, indices, program, type, vertexLayout, vertexCount, offset, blending, uniforms)
		}
		//VertexFormat()
		//	.add("hello", VertexFormat.Element.Type.Byte4)
	}

	protected fun checkBuffers(vertices: AG.Buffer, indices: AG.Buffer) {
		if (vertices.kind != AG.Buffer.Kind.VERTEX) invalidOp("Not a VertexBuffer")
		if (indices.kind != AG.Buffer.Kind.INDEX) invalidOp("Not a IndexBuffer")
	}

	open fun disposeTemporalPerFrameStuff() = Unit

	val frameRenderBuffers = java.util.ArrayList<RenderBuffer>()
	val renderBuffers = Pool<RenderBuffer>() { createRenderBuffer() }

	open inner class RenderBuffer : Closeable {
		val tex = this@AG.createTexture()

		open fun start(width: Int, height: Int) = Unit
		open fun end() = Unit
		open fun readBitmap(bmp: Bitmap32) = Unit
		override fun close() = Unit
	}

	open protected fun createRenderBuffer() = RenderBuffer()

	fun flip() {
		disposeTemporalPerFrameStuff()
		renderBuffers.free(frameRenderBuffers)
		frameRenderBuffers.clear()
		flipInternal()
	}

	protected open fun flipInternal() = Unit

	open fun clear(color: Int = Colors.TRANSPARENT_BLACK, depth: Float = 0f, stencil: Int = 0, clearColor: Boolean = true, clearDepth: Boolean = true, clearStencil: Boolean = true) = Unit

	class RenderTexture(val tex: Texture, val width: Int, val height: Int)

	var renderingToTexture = false

	inline fun renderToTexture(width: Int, height: Int, callback: () -> Unit): RenderTexture {
		val oldRendering = renderingToTexture
		val oldWidth = backWidth
		val oldHeight = backHeight
		renderingToTexture = true
		backWidth = width
		backHeight = height
		try {
			return renderToTextureInternal(width, height, callback)
		} finally {
			renderingToTexture = oldRendering
			backWidth = oldWidth
			backHeight = oldHeight
		}
	}

	inline fun renderToTextureInternal(width: Int, height: Int, callback: () -> Unit): RenderTexture {
		val rb = renderBuffers.alloc()
		frameRenderBuffers += rb
		val oldRendering = renderingToTexture
		renderingToTexture = true

		rb.start(width, height)
		try {
			clear(0) // transparent
			callback()
		} finally {
			rb.end()
			renderingToTexture = oldRendering
		}
		return RenderTexture(rb.tex, width, height)
	}

	inline fun renderToBitmap(bmp: Bitmap32, callback: () -> Unit) {
		val rb = renderBuffers.alloc()
		frameRenderBuffers += rb
		val oldRendering = renderingToTexture
		renderingToTexture = true

		rb.start(bmp.width, bmp.height)
		try {
			clear(0)
			callback()
		} finally {
			rb.readBitmap(bmp)
			rb.end()
			renderingToTexture = oldRendering
		}
	}
}