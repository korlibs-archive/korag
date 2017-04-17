package com.soywiz.korag.log

import com.soywiz.korag.AG
import com.soywiz.korag.shader.Program
import com.soywiz.korag.shader.Uniform
import com.soywiz.korag.shader.VarType
import com.soywiz.korag.shader.VertexLayout
import com.soywiz.korim.bitmap.Bitmap32
import com.soywiz.korim.bitmap.Bitmap8
import java.nio.ByteBuffer

open class LogAG(
	width: Int = 640,
	height: Int = 480
) : AG() {
	val log = arrayListOf<String>()
	override val nativeComponent: Any = Object()

	init {
		ready()
	}

	private fun log(str: String) {
		this.log += str
		//println(str)
	}

	fun getLogAsString(): String = log.joinToString("\n")

	override fun clear(color: Int, depth: Float, stencil: Int, clearColor: Boolean, clearDepth: Boolean, clearStencil: Boolean) = log("clear($color, $depth, $stencil, $clearColor, $clearDepth, $clearStencil)")
	override var backWidth: Int = width; set(value) = run { field = value; log("backWidth = $value") }
	override var backHeight: Int = height; set(value) = run { field = value; log("backHeight = $value") }

	override fun repaint() = log("repaint()")

	override fun resized() {
		log("resized()")
		onResized(Unit)
	}

	override fun dispose() = log("dispose()")

	inner class LogTexture(val id: Int) : Texture() {
		override fun createMipmaps(): Boolean = true.also { log("$this.createMipmaps()") }
		override fun uploadBuffer(data: ByteBuffer, width: Int, height: Int, kind: Kind) = log("$this.uploadBuffer($data, $width, $height, $kind)")
		override fun uploadBitmap32(bmp: Bitmap32) = log("$this.uploadBitmap32($bmp)")
		override fun uploadBitmap8(bmp: Bitmap8) = log("$this.uploadBitmap8($bmp)")
		override fun close() = log("$this.close()")
		override fun toString(): String = "Texture[$id]"
	}

	inner class LogBuffer(val id: Int, kind: Kind) : Buffer(kind) {
		val logmem get() = mem
		override fun afterSetMem() = log("$this.afterSetMem(mem[${mem.length}])")
		override fun close() = log("$this.close()")
		override fun toString(): String = "Buffer[$id]"
	}

	inner class LogRenderBuffer(val id: Int) : RenderBuffer() {
		override fun start(width: Int, height: Int) = log("$this.start($width, $height)")
		override fun end() = log("$this.end()")
		override fun readBitmap(bmp: Bitmap32) = log("$this.readBitmap($bmp)")
		override fun close() = log("$this.close()")
		override fun toString(): String = "RenderBuffer[$id]"
	}

	private var textureId = 0
	private var bufferId = 0
	private var renderBufferId = 0

	override fun createTexture(): Texture = LogTexture(textureId++).apply { log("createTexture():$id") }

	override fun createBuffer(kind: Buffer.Kind): Buffer = LogBuffer(bufferId++, kind).apply { log("createBuffer($kind):$id") }
	override fun draw(vertices: Buffer, indices: Buffer, program: Program, type: DrawType, vertexLayout: VertexLayout, vertexCount: Int, offset: Int, blending: BlendFactors, uniforms: Map<Uniform, Any>) {
		try {
			log("draw(vertices=$vertices, indices=$indices, program=$program, type=$type, vertexLayout=$vertexLayout, vertexCount=$vertexCount, offset=$offset, blending=$blending, uniforms=$uniforms)")

			val missingUniforms = program.uniforms - uniforms.keys
			val extraUniforms = uniforms.keys - program.uniforms
			val missingAttributes = vertexLayout.attributes.toSet() - program.attributes
			val extraAttributes = program.attributes - vertexLayout.attributes.toSet()

			if (missingUniforms.isNotEmpty()) log("::draw.ERROR.Missing:$missingUniforms")
			if (extraUniforms.isNotEmpty()) log("::draw.ERROR.Unexpected:$extraUniforms")

			if (missingAttributes.isNotEmpty()) log("::draw.ERROR.Missing:$missingAttributes")
			if (extraAttributes.isNotEmpty()) log("::draw.ERROR.Unexpected:$extraAttributes")

			val vertexMem = (vertices as LogBuffer).logmem
			val indexMem = (indices as LogBuffer).logmem
			val indices = (offset until offset + vertexCount).map { indexMem.getAlignedInt16(it) }
			log("::draw.indices=$indices")
			for (index in indices.sorted().distinct()) {
				val os = index * vertexLayout.totalSize
				val attributes = arrayListOf<String>()
				for ((attribute, pos) in vertexLayout.attributes.zip(vertexLayout.attributePositions)) {
					val o = os + pos

					val info = when (attribute.type) {
						VarType.Int1 -> "int(" + vertexMem.getInt32(o + 0) + ")"
						VarType.Float1 -> "float(" + vertexMem.getFloat32(o + 0) + ")"
						VarType.Float2 -> "vec2(" + vertexMem.getFloat32(o + 0) + "," + vertexMem.getFloat32(o + 4) + ")"
						VarType.Float3 -> "vec2(" + vertexMem.getFloat32(o + 0) + "," + vertexMem.getFloat32(o + 4) + "," + vertexMem.getFloat32(o + 8) + ")"
						VarType.Byte4 -> "byte4(" + vertexMem.getInt32(o + 0) + ")"
						else -> "Unsupported(${attribute.type})"
					}

					attributes += attribute.name + "[" + info + "]"
				}
				log("::draw.vertex[$index]: " + attributes.joinToString(", "))
			}
		} catch (e: Throwable) {
			log("ERROR: ${e.message}")
			e.printStackTrace()
		}
	}

	override fun disposeTemporalPerFrameStuff() = log("disposeTemporalPerFrameStuff()")
	override fun createRenderBuffer(): RenderBuffer = LogRenderBuffer(renderBufferId++).apply { log("createRenderBuffer():$id") }
	override fun flipInternal() = log("flipInternal()")
}