package com.soywiz.korag.webgl

import com.jtransc.FastMemory
import com.jtransc.JTranscArrays
import com.jtransc.annotation.JTranscMethodBody
import com.jtransc.io.JTranscConsole
import com.jtransc.js.*
import com.soywiz.korag.AG
import com.soywiz.korag.AGFactory
import com.soywiz.korag.BlendMode
import com.soywiz.korag.geom.Matrix4
import com.soywiz.korag.shader.*
import com.soywiz.korim.bitmap.Bitmap32
import com.soywiz.korim.bitmap.Bitmap8
import com.soywiz.korim.color.RGBA
import com.soywiz.korio.error.invalidOp
import com.soywiz.korio.util.UByteArray
import java.io.Closeable
import java.nio.ByteBuffer

class AGFactoryWebgl : AGFactory() {
	override val priority: Int = 1500
	override fun create(): AG = AGWebgl()
}

class AGWebgl : AG() {
	val canvas = document.methods["createElement"]("canvas")
	val glOpts = jsObject("premultipliedAlpha" to false)
	val gl = canvas.methods["getContext"]("webgl", glOpts) ?: canvas.methods["getContext"]("experimental-webgl", glOpts)

	override fun clear() {
		gl.methods["clearColor"](RGBA.getRf(clearColor), RGBA.getGf(clearColor), RGBA.getBf(clearColor), RGBA.getAf(clearColor))
		gl.methods["clearDepth"](0f)
		gl.methods["clearStencil"](0)
		gl.methods["clear"](gl["COLOR_BUFFER_BIT"].toInt() or gl["DEPTH_BUFFER_BIT"].toInt() or gl["STENCIL_BUFFER_BIT"].toInt())
	}

	class WebglProgram(val backend: AGWebgl, val p: Program) : Closeable {
		val gl = backend.gl

		var program = gl.methods["createProgram"]()

		fun createShader(type: JsDynamic?, source: String): JsDynamic? {
			val shader = gl.methods["createShader"](type)
			gl.methods["shaderSource"](shader, source)
			gl.methods["compileShader"](shader)

			val success = gl.methods["getShaderParameter"](shader, gl["COMPILE_STATUS"]).toBool()
			if (!success) {
				val error = gl.methods["getShaderInfoLog"](shader).toJavaStringOrNull()
				JTranscConsole.error(shader)
				JTranscConsole.error(source)
				JTranscConsole.error("Could not compile WebGL shader: " + error)
				throw RuntimeException(error)
			}
			return shader
		}

		var vertex = createShader(gl["VERTEX_SHADER"], p.vertex.toGlSlString())
		var fragment = createShader(gl["FRAGMENT_SHADER"], p.fragment.toGlSlString())

		init {
			gl.methods["attachShader"](program, vertex)
			gl.methods["attachShader"](program, fragment)

			gl.methods["linkProgram"](program)

			if (!gl.methods["getProgramParameter"](program, gl["LINK_STATUS"]).toBool()) {
				val info = gl.methods["getProgramInfoLog"](program)
				JTranscConsole.error("Could not compile WebGL program: " + info)
			}
		}


		fun bind() {
			gl.methods["useProgram"](this.program)
		}

		fun unbind() {
			gl.methods["userProgram"](null)
		}

		override fun close() {
			gl.methods["deleteShader"](this.vertex)
			gl.methods["deleteShader"](this.fragment)
			gl.methods["deleteProgram"](this.program)
		}
	}

	class WebglTexture(val backend: AGWebgl) : Texture() {
		val gl = backend.gl
		val tex = gl.methods["createTexture"]()

		override fun createMipmaps(): Boolean {
			bind()
			setFilter(true)
			setWrapST()
			//gl.methods["generateMipmap"](gl["TEXTURE_2D"])
			return false
		}

		fun uploadBuffer(data: Any, width: Int, height: Int, rgba: Boolean) {
			val Bpp = if (rgba) 4 else 1
			val rdata = jsNew("Uint8Array", data.asJsDynamic().methods["getBuffer"](), 0, width * height * Bpp)
			val type = if (rgba) gl["RGBA"] else gl["LUMINANCE"]
			bind()
			gl.methods["texImage2D"](gl["TEXTURE_2D"], 0, type, width, height, 0, type, gl["UNSIGNED_BYTE"], rdata)
		}

		override fun uploadBuffer(data: ByteBuffer, width: Int, height: Int, kind: Kind) = uploadBuffer(data.array(), width, height, kind == Kind.RGBA)
		override fun uploadBitmap32(bmp: Bitmap32) = uploadBuffer(bmp.data, bmp.width, bmp.height, true)
		override fun uploadBitmap8(bmp: Bitmap8) = uploadBuffer(bmp.data, bmp.width, bmp.height, false)

		fun bind(): Unit = run { gl.methods["bindTexture"](gl["TEXTURE_2D"], tex) }
		fun unbind(): Unit = run { gl.methods["bindTexture"](gl["TEXTURE_2D"], null) }

		override fun close(): Unit = run { gl.methods["deleteTexture"](tex) }

		fun setFilter(linear: Boolean) {
			val minFilter = if (this.mipmaps) {
				if (linear) gl["LINEAR_MIPMAP_NEAREST"] else gl["NEAREST_MIPMAP_NEAREST"]
			} else {
				if (linear) gl["LINEAR"] else gl["NEAREST"]
			}
			val magFilter = if (linear) gl["LINEAR"] else gl["NEAREST"]

			setWrapST()
			setMinMag(minFilter.toInt(), magFilter.toInt())
		}

		private fun setWrapST() {
			gl.methods["texParameteri"](gl["TEXTURE_2D"], gl["TEXTURE_WRAP_S"], gl["CLAMP_TO_EDGE"])
			gl.methods["texParameteri"](gl["TEXTURE_2D"], gl["TEXTURE_WRAP_T"], gl["CLAMP_TO_EDGE"])
		}

		private fun setMinMag(min: Int, mag: Int) {
			gl.methods["texParameteri"](gl["TEXTURE_2D"], gl["TEXTURE_MIN_FILTER"], min)
			gl.methods["texParameteri"](gl["TEXTURE_2D"], gl["TEXTURE_MAG_FILTER"], mag)
		}
	}

	class WebglBuffer(backend: AGWebgl, kind: Kind) : Buffer(kind) {
		val gl = backend.gl

		val buffer = gl.methods["createBuffer"]()
		val target = if (kind == Kind.INDEX) gl["ELEMENT_ARRAY_BUFFER"] else gl["ARRAY_BUFFER"]

		fun bind() {
			gl.methods["bindBuffer"](this.target, this.buffer)
		}

		fun uploadInternal(data: ByteArray, offset: Int, length: Int): Buffer {
			gl.methods["bufferData"](this.target, data, gl["STATIC_DRAW"])
			return this
		}

		override fun upload(data: ByteBuffer, offset: Int, length: Int): Buffer {
			bind()
			uploadInternal(data.array(), offset, length)
			return this
		}

		override fun upload(data: FastMemory, offset: Int, length: Int): Buffer {
			bind()
			gl.methods["bufferData"](this.target, data.asJsDynamic()["buffer"], gl["STATIC_DRAW"])
			return this
		}

		override fun close() {
			gl.methods["deleteBuffer"](buffer)
		}
	}

	override fun createTexture(): Texture = WebglTexture(this)
	override fun createBuffer(kind: Buffer.Kind): Buffer = WebglBuffer(this, kind)

	private val programs = hashMapOf<Program, WebglProgram>()

	fun getProgram(program: Program): WebglProgram = programs.getOrPut(program) { WebglProgram(this, program) }

	val VarType.webglElementType: Int get() = when (this) {
		VarType.Float1, VarType.Float2, VarType.Float3, VarType.Float4 -> gl["FLOAT"].toInt()
		VarType.Mat4 -> gl["FLOAT"].toInt()
		VarType.Bool1 -> gl["UNSIGNED_BYTE"].toInt()
		VarType.Byte4 -> gl["UNSIGNED_BYTE"].toInt()
		VarType.TextureUnit -> gl["INT"].toInt()
	}

	val DrawType.glDrawMode: Int get() = when (this) {
		DrawType.TRIANGLES -> gl["TRIANGLES"].toInt()
	}

	override fun draw(vertices: Buffer, indices: Buffer, program: Program, type: DrawType, vertexFormat: VertexFormat, vertexCount: Int, offset: Int, blending: BlendMode, uniforms: Map<Uniform, Any>) {
		checkBuffers(vertices, indices)
		val glProgram = getProgram(program)
		(vertices as WebglBuffer).bind()
		(indices as WebglBuffer).bind()
		glProgram.bind()

		for (n in vertexFormat.attributePositions.indices) {
			val att = vertexFormat.attributes[n]
			val off = vertexFormat.attributePositions[n]
			val loc = gl.methods["getAttribLocation"](glProgram.program, att.name).toInt()
			val glElementType = att.type.webglElementType
			val elementCount = att.type.elementCount
			val totalSize = vertexFormat.totalSize
			if (loc >= 0) {
				gl.methods["enableVertexAttribArray"](loc)
				gl.methods["vertexAttribPointer"](loc, elementCount, glElementType, att.normalized, totalSize, off)
			}
		}
		var textureUnit = 0
		for ((uniform, value) in uniforms) {
			val location = glGetUniformLocation(glProgram, uniform.name) ?: continue
			when (uniform.type) {
				VarType.TextureUnit -> {
					val unit = value as TextureUnit
					gl.methods["activeTexture"](gl["TEXTURE0"].toInt() + textureUnit)
					val tex = (unit.texture as WebglTexture?)
					tex?.bind()
					tex?.setFilter(unit.linear)
					gl.methods["uniform1i"](location, textureUnit)
					textureUnit++
				}
				VarType.Mat4 -> {
					glUniformMatrix4fv(location, false, (value as Matrix4).data)
				}
				VarType.Float1 -> {
					gl.methods["uniform1f"](location, (value as Number).toFloat())
				}
				else -> invalidOp("Don't know how to set uniform ${uniform.type}")
			}
		}

		when (blending) {
			BlendMode.NONE -> {
				gl.methods["disable"](gl["BLEND"])
			}
			BlendMode.OVERLAY -> {
				gl.methods["enable"](gl["BLEND"])
				gl.methods["blendFuncSeparate"](gl["SRC_ALPHA"], gl["ONE_MINUS_SRC_ALPHA"], gl["ONE"], gl["ONE_MINUS_SRC_ALPHA"])
			}
			else -> Unit
		}

		//gl.methods["drawArrays"](type.glDrawMode, 0, 3)
		gl.methods["drawElements"](type.glDrawMode, vertexCount, gl["UNSIGNED_SHORT"], offset)

		gl.methods["activeTexture"](gl["TEXTURE0"])
		for (att in vertexFormat.attributes) {
			val loc = gl.methods["getAttribLocation"](glProgram, att.name).toInt()
			if (loc >= 0) {
				gl.methods["disableVertexAttribArray"](loc)
			}
		}
	}

	private fun glUniformMatrix4fv(location: Any, b: Boolean, values: FloatArray) {
		gl.methods["uniformMatrix4fv"](location, b, values.asJsDynamic()["data"])
	}

	private fun glGetUniformLocation(glProgram: WebglProgram, name: String): Any? {
		return gl.methods["getUniformLocation"](glProgram.program, name)
	}

	val tempTextures = arrayListOf<Texture>()

	override fun disposeTemporalPerFrameStuff() {
		for (tt in tempTextures) tt.close()
		tempTextures.clear()
	}

	override fun flipInternal() {
	}

	class WebglRenderBuffer(ag: AGWebgl) : RenderBuffer(ag) {
		val gl = ag.gl
		val wtex = tex as WebglTexture

		val renderbuffer = gl.methods["createRenderbuffer"]()
		val framebuffer = gl.methods["createFramebuffer"]()
		var oldViewport = IntArray(4)

		override fun start(width: Int, height: Int) {
			oldViewport = gl.methods["getParameter"](gl["VIEWPORT"]).toIntArray()
			//println("oldViewport:${oldViewport.toList()}")
			gl.methods["bindTexture"](gl["TEXTURE_2D"], wtex.tex)
			gl.methods["texParameteri"](gl["TEXTURE_2D"], gl["TEXTURE_MAG_FILTER"], gl["LINEAR"])
			gl.methods["texParameteri"](gl["TEXTURE_2D"], gl["TEXTURE_MIN_FILTER"], gl["LINEAR"])
			gl.methods["texImage2D"](gl["TEXTURE_2D"], 0, gl["RGBA"], width, height, 0, gl["RGBA"], gl["UNSIGNED_BYTE"], null)
			gl.methods["bindTexture"](gl["TEXTURE_2D"], null)
			gl.methods["bindRenderbuffer"](gl["RENDERBUFFER"], renderbuffer)
			gl.methods["bindFramebuffer"](gl["FRAMEBUFFER"], framebuffer)
			gl.methods["framebufferTexture2D"](gl["FRAMEBUFFER"], gl["COLOR_ATTACHMENT0"], gl["TEXTURE_2D"], wtex.tex, 0)
			gl.methods["renderbufferStorage"](gl["RENDERBUFFER"], gl["DEPTH_COMPONENT16"], width, height)
			gl.methods["framebufferRenderbuffer"](gl["FRAMEBUFFER"], gl["DEPTH_ATTACHMENT"], gl["RENDERBUFFER"], renderbuffer)
			gl.methods["viewport"](0, 0, width, height)
		}

		override fun end() {
			gl.methods["flush"]()
			gl.methods["bindTexture"](gl["TEXTURE_2D"], null)
			gl.methods["bindRenderbuffer"](gl["RENDERBUFFER"], null)
			gl.methods["bindFramebuffer"](gl["FRAMEBUFFER"], null)
			gl.methods["viewport"](oldViewport[0], oldViewport[1], oldViewport[2], oldViewport[3])
		}

		override fun readBitmap(bmp: Bitmap32) {
			val data = UByteArray(bmp.area * 4)

			gl.methods["readPixels"](0, 0, bmp.width, bmp.height, gl["RGBA"], gl["UNSIGNED_BYTE"], jsNew("Uint8Array", data.data.asJsDynamic()["data"]["buffer"]))

			val ibuffer = JTranscArrays.nativeReinterpretAsInt(data.data)
			for (n in 0 until bmp.area) bmp.data[n] = RGBA.rgbaToBgra(ibuffer[n])
		}

		override fun close() {
			gl.methods["deleteFramebuffer"](framebuffer)
			gl.methods["deleteRenderbuffer"](renderbuffer)
		}
	}

	override fun createRenderBuffer(): RenderBuffer = WebglRenderBuffer(this)
}

@JTranscMethodBody(target = "js", value = "return JA_I.fromTypedArray(new Int32Array(p0));")
external fun JsDynamic?.toIntArray(): IntArray