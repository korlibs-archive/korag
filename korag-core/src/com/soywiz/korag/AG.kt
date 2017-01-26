package com.soywiz.korag

import com.jtransc.FastMemory
import com.soywiz.korag.shader.Program
import com.soywiz.korag.shader.Uniform
import com.soywiz.korag.shader.VertexLayout
import com.soywiz.korim.bitmap.Bitmap
import com.soywiz.korim.bitmap.Bitmap32
import com.soywiz.korim.bitmap.Bitmap8
import com.soywiz.korim.color.RGBA
import com.soywiz.korio.error.invalidOp
import com.soywiz.korio.util.Pool
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*

val agFactory by lazy {
	ServiceLoader.load(AGFactory::class.java).toList().filter(AGFactory::available).sortedBy(AGFactory::priority).firstOrNull()
		?: invalidOp("Can't find AGFactory implementation")
}

abstract class AGFactory {
	open val available: Boolean = true
	open val priority: Int = 4000

	abstract fun create(): AG
}

abstract class AG {
	abstract val nativeComponent: Any
	open var backWidth: Int = 640
	open var backHeight: Int = 480

	var onRender: (AG) -> Unit = {}

	open fun repaint() {
	}

	open fun resized() {
	}

	open class Texture : Closeable {
		var mipmaps = false

		fun upload(bmp: Bitmap, mipmaps: Boolean = false): Texture {
			when (bmp) {
				is Bitmap8 -> uploadBitmap8(bmp)
				is Bitmap32 -> uploadBitmap32(bmp)
				else -> invalidOp("Unknown bitmap type: $bmp")
			}
			this.mipmaps = if (mipmaps) createMipmaps() else false
			return this
		}

		enum class Kind { RGBA, LUMINANCE }

		open protected fun createMipmaps() = false

		open fun uploadBuffer(data: ByteBuffer, width: Int, height: Int, kind: Kind) {
		}

		open fun uploadBitmap32(bmp: Bitmap32) {
			val buffer = ByteBuffer.allocateDirect(bmp.area * 4).order(ByteOrder.nativeOrder())
			val intBuffer = buffer.asIntBuffer()
			//intBuffer.clear()
			for (n in 0 until bmp.area) intBuffer.put(bmp.data[n])
			//intBuffer.flip()
			//buffer.limit(intBuffer.limit() * 4)
			uploadBuffer(buffer, bmp.width, bmp.height, Kind.RGBA)
		}

		open fun uploadBitmap8(bmp: Bitmap8) {
			val buffer = ByteBuffer.allocateDirect(bmp.area * 4).order(ByteOrder.nativeOrder())
			//buffer.clear()
			buffer.put(bmp.data)
			//buffer.flip()
			uploadBuffer(buffer, bmp.width, bmp.height, Kind.LUMINANCE)
		}

		override fun close() {
		}
	}

	class TextureUnit {
		var texture: AG.Texture? = null
		var linear: Boolean = true
	}

	open class Buffer(val kind: Kind) : Closeable {
		enum class Kind { INDEX, VERTEX }

		var dirty = false
		protected var mem: FastMemory = FastMemory.alloc(0)

		open fun afterSetMem() {
		}

		fun upload(data: ByteBuffer, offset: Int = 0, length: Int = data.limit()): Buffer {
			// @TODO: Optimize this
			mem = FastMemory.alloc(length)
			for (n in 0 until length) {
				mem.setInt8(n, data.get(offset + n).toInt())
			}
			dirty = true
			afterSetMem()
			return this
		}

		fun upload(data: ByteArray, offset: Int = 0, length: Int = data.size): Buffer {
			mem = FastMemory.alloc(length)
			mem.setArrayInt8(0, data, offset, length)
			dirty = true
			afterSetMem()
			return this
		}

		fun upload(data: FloatArray, offset: Int = 0, length: Int = data.size): Buffer {
			mem = FastMemory.alloc(length * 4)
			mem.setArrayFloat32(0, data, offset, length)
			dirty = true
			afterSetMem()
			return this
		}

		fun upload(data: IntArray, offset: Int = 0, length: Int = data.size): Buffer {
			mem = FastMemory.alloc(length * 4)
			mem.setArrayInt32(0, data, offset, length)
			dirty = true
			afterSetMem()
			return this
		}

		fun upload(data: ShortArray, offset: Int = 0, length: Int = data.size): Buffer {
			mem = FastMemory.alloc(length * 2)
			mem.setArrayInt16(0, data, offset, length)
			dirty = true
			afterSetMem()
			return this

		}

		fun upload(data: FastMemory, offset: Int = 0, length: Int = data.length): Buffer {
			mem = FastMemory.alloc(length)
			FastMemory.copy(data, offset, mem, 0, length)
			dirty = true
			afterSetMem()
			return this
		}

		override fun close() {
		}
	}

	enum class DrawType {
		TRIANGLES, TRIANGLE_STRIP
	}

	open fun createTexture(): Texture = Texture()
	fun createTexture(bmp: Bitmap, mipmaps: Boolean = false): Texture = createTexture().upload(bmp, mipmaps)
	open fun createBuffer(kind: Buffer.Kind) = Buffer(kind)
	fun createIndexBuffer() = createBuffer(Buffer.Kind.INDEX)
	fun createVertexBuffer() = createBuffer(Buffer.Kind.VERTEX)

	fun createIndexBuffer(data: ShortArray, offset: Int = 0, length: Int = data.size - offset) = createIndexBuffer().apply {
		upload(data, offset, length)
	}

	fun createVertexBuffer(data: FloatArray, offset: Int = 0, length: Int = data.size - offset) = createVertexBuffer().apply {
		upload(data, offset, length)
	}

	fun createVertexBuffer(data: FastMemory, offset: Int = 0, length: Int = data.length - offset) = createVertexBuffer().apply {
		upload(data, offset, length)
	}

	open fun draw(vertices: Buffer, indices: Buffer, program: Program, type: DrawType, vertexLayout: VertexLayout, vertexCount: Int, offset: Int = 0, blending: BlendMode = BlendMode.OVERLAY, uniforms: Map<Uniform, Any> = mapOf()) {
		//VertexFormat()
		//	.add("hello", VertexFormat.Element.Type.Byte4)
	}

	fun draw(vertices: Buffer, program: Program, type: DrawType, vertexLayout: VertexLayout, vertexCount: Int, offset: Int = 0, blending: BlendMode = BlendMode.OVERLAY, uniforms: Map<Uniform, Any> = mapOf()) {
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

	open class RenderBuffer(val ag: AG) : Closeable {
		val tex = ag.createTexture()

		open fun start(width: Int, height: Int) = Unit
		open fun end() = Unit
		open fun readBitmap(bmp: Bitmap32) = Unit
		override fun close() = Unit
	}

	open protected fun createRenderBuffer() = RenderBuffer(this)

	fun flip() {
		disposeTemporalPerFrameStuff()
		renderBuffers.free(frameRenderBuffers)
		frameRenderBuffers.clear()
		flipInternal()
	}

	protected open fun flipInternal() = Unit

	open fun clear(color: Int = RGBA(0, 0, 0, 0xFF), depth: Float = 0f, stencil: Int = 0, clearColor: Boolean = true, clearDepth: Boolean = true, clearStencil: Boolean = true) = Unit

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
		val rb = renderBuffers.obtain()
		frameRenderBuffers += rb
		val oldRendering = renderingToTexture
		renderingToTexture = true

		rb.start(width, height)
		clear()
		try {
			callback()
		} finally {
			rb.end()
			renderingToTexture = oldRendering
		}
		return RenderTexture(rb.tex, width, height)
	}

	inline fun renderToBitmap(bmp: Bitmap32, callback: () -> Unit) {
		val rb = renderBuffers.obtain()
		frameRenderBuffers += rb
		val oldRendering = renderingToTexture
		renderingToTexture = true

		rb.start(bmp.width, bmp.height)
		clear()
		try {
			callback()
		} finally {
			rb.readBitmap(bmp)
			rb.end()
			renderingToTexture = oldRendering
		}
	}
}