package com.soywiz.korag.webgl

import com.jtransc.JTranscArrays
import com.jtransc.annotation.JTranscMethodBody
import com.jtransc.io.JTranscConsole
import com.jtransc.js.*
import com.soywiz.korag.AG
import com.soywiz.korag.AGFactory
import com.soywiz.korag.BlendMode
import com.soywiz.korag.geom.Matrix4
import com.soywiz.korag.shader.Program
import com.soywiz.korag.shader.Uniform
import com.soywiz.korag.shader.VarType
import com.soywiz.korag.shader.VertexLayout
import com.soywiz.korag.shader.gl.toGlSlString
import com.soywiz.korim.bitmap.Bitmap32
import com.soywiz.korim.bitmap.Bitmap8
import com.soywiz.korim.color.RGBA
import com.soywiz.korio.error.invalidOp
import com.soywiz.korio.util.OS
import com.soywiz.korio.util.UByteArray
import java.io.Closeable
import java.nio.ByteBuffer

class AGFactoryWebgl : AGFactory() {
	override val available: Boolean = OS.isJs
	override val priority: Int = 1500
	override fun create(): AG = AGWebgl()
}

class AGWebgl : AG() {
	val canvas = document.methods["createElement"]("canvas")!!
	val glOpts = jsObject("premultipliedAlpha" to false)
	val gl = canvas.methods["getContext"]("webgl", glOpts) ?: canvas.methods["getContext"]("experimental-webgl", glOpts)
	val glm = gl.methods
	override val nativeComponent: Any = canvas

	override fun repaint() {
		onRender(this)
	}

	override fun resized() {
		glm["viewport"](0, 0, canvas["width"], canvas["height"])
	}

	override fun clear(color: Int, depth: Float, stencil: Int, clearColor: Boolean, clearDepth: Boolean, clearStencil: Boolean) {
		glm["clearColor"](RGBA.getRf(color), RGBA.getGf(color), RGBA.getBf(color), RGBA.getAf(color))
		glm["clearDepth"](depth)
		glm["clearStencil"](stencil)
		var flags = 0
		if (clearColor) flags = flags or gl["COLOR_BUFFER_BIT"].toInt()
		if (clearDepth) flags = flags or gl["DEPTH_BUFFER_BIT"].toInt()
		if (clearStencil) flags = flags or gl["STENCIL_BUFFER_BIT"].toInt()
		glm["clear"](flags)
	}

	inner class WebglProgram(val p: Program) : Closeable {
		var program = glm["createProgram"]()

		fun createShader(type: JsDynamic?, source: String): JsDynamic? {
			val shader = glm["createShader"](type)
			glm["shaderSource"](shader, source)
			glm["compileShader"](shader)

			val success = glm["getShaderParameter"](shader, gl["COMPILE_STATUS"]).toBool()
			if (!success) {
				val error = glm["getShaderInfoLog"](shader).toJavaStringOrNull()
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
			glm["attachShader"](program, vertex)
			glm["attachShader"](program, fragment)

			glm["linkProgram"](program)

			if (!glm["getProgramParameter"](program, gl["LINK_STATUS"]).toBool()) {
				val info = glm["getProgramInfoLog"](program)
				JTranscConsole.error("Could not compile WebGL program: " + info)
			}
		}


		fun bind() {
			glm["useProgram"](this.program)
		}

		fun unbind() {
			glm["userProgram"](null)
		}

		override fun close() {
			glm["deleteShader"](this.vertex)
			glm["deleteShader"](this.fragment)
			glm["deleteProgram"](this.program)
		}
	}

	inner class WebglTexture() : Texture() {
		val tex = glm["createTexture"]()

		override fun createMipmaps(): Boolean {
			bind()
			setFilter(true)
			setWrapST()
			//glm["generateMipmap"](gl["TEXTURE_2D"])
			return false
		}

		fun uploadBuffer(data: Any, width: Int, height: Int, rgba: Boolean) {
			val Bpp = if (rgba) 4 else 1
			val rdata = jsNew("Uint8Array", data.asJsDynamic().methods["getBuffer"](), 0, width * height * Bpp)
			val type = if (rgba) gl["RGBA"] else gl["LUMINANCE"]
			bind()
			glm["texImage2D"](gl["TEXTURE_2D"], 0, type, width, height, 0, type, gl["UNSIGNED_BYTE"], rdata)
		}

		override fun uploadBuffer(data: ByteBuffer, width: Int, height: Int, kind: Kind) = uploadBuffer(data.array(), width, height, kind == Kind.RGBA)
		override fun uploadBitmap32(bmp: Bitmap32) = uploadBuffer(bmp.data, bmp.width, bmp.height, true)
		override fun uploadBitmap8(bmp: Bitmap8) = uploadBuffer(bmp.data, bmp.width, bmp.height, false)

		fun bind(): Unit = run { glm["bindTexture"](gl["TEXTURE_2D"], tex) }
		fun unbind(): Unit = run { glm["bindTexture"](gl["TEXTURE_2D"], null) }

		override fun close(): Unit = run { glm["deleteTexture"](tex) }

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
			glm["texParameteri"](gl["TEXTURE_2D"], gl["TEXTURE_WRAP_S"], gl["CLAMP_TO_EDGE"])
			glm["texParameteri"](gl["TEXTURE_2D"], gl["TEXTURE_WRAP_T"], gl["CLAMP_TO_EDGE"])
		}

		private fun setMinMag(min: Int, mag: Int) {
			glm["texParameteri"](gl["TEXTURE_2D"], gl["TEXTURE_MIN_FILTER"], min)
			glm["texParameteri"](gl["TEXTURE_2D"], gl["TEXTURE_MAG_FILTER"], mag)
		}
	}

	inner class WebglBuffer(kind: Kind) : Buffer(kind) {
		val buffer = glm["createBuffer"]()
		val target = if (kind == Kind.INDEX) gl["ELEMENT_ARRAY_BUFFER"] else gl["ARRAY_BUFFER"]

		fun bind() {
			glm["bindBuffer"](this.target, this.buffer)
		}

		override fun afterSetMem() {
			bind()
			val buffer = mem.asJsDynamic()["buffer"]
			val typedArray = jsNew("Int8Array", buffer, 0, mem.length)
			//console.methods["log"](target)
			//console.methods["log"](typedArray)
			glm["bufferData"](this.target, typedArray, gl["STATIC_DRAW"])
		}

		override fun close() {
			glm["deleteBuffer"](buffer)
		}
	}

	override fun createTexture(): Texture = WebglTexture()
	override fun createBuffer(kind: Buffer.Kind): Buffer = WebglBuffer(kind)

	private val programs = hashMapOf<Program, WebglProgram>()

	fun getProgram(program: Program): WebglProgram = programs.getOrPut(program) { WebglProgram(program) }

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

	override fun draw(vertices: Buffer, indices: Buffer, program: Program, type: DrawType, vertexLayout: VertexLayout, vertexCount: Int, offset: Int, blending: BlendMode, uniforms: Map<Uniform, Any>) {
		checkBuffers(vertices, indices)
		val glProgram = getProgram(program)
		(vertices as WebglBuffer).bind()
		(indices as WebglBuffer).bind()
		glProgram.bind()

		for (n in vertexLayout.attributePositions.indices) {
			val att = vertexLayout.attributes[n]
			val off = vertexLayout.attributePositions[n]
			val loc = glm["getAttribLocation"](glProgram.program, att.name).toInt()
			val glElementType = att.type.webglElementType
			val elementCount = att.type.elementCount
			val totalSize = vertexLayout.totalSize
			if (loc >= 0) {
				glm["enableVertexAttribArray"](loc)
				glm["vertexAttribPointer"](loc, elementCount, glElementType, att.normalized, totalSize, off)
			}
		}
		var textureUnit = 0
		for ((uniform, value) in uniforms) {
			val location = glGetUniformLocation(glProgram, uniform.name) ?: continue
			when (uniform.type) {
				VarType.TextureUnit -> {
					val unit = value as TextureUnit
					glm["activeTexture"](gl["TEXTURE0"].toInt() + textureUnit)
					val tex = (unit.texture as WebglTexture?)
					tex?.bind()
					tex?.setFilter(unit.linear)
					glm["uniform1i"](location, textureUnit)
					textureUnit++
				}
				VarType.Mat4 -> {
					glUniformMatrix4fv(location, false, (value as Matrix4).data)
				}
				VarType.Float1 -> {
					glm["uniform1f"](location, (value as Number).toFloat())
				}
				else -> invalidOp("Don't know how to set uniform ${uniform.type}")
			}
		}

		when (blending) {
			BlendMode.NONE -> {
				glm["disable"](gl["BLEND"])
			}
			BlendMode.OVERLAY -> {
				glm["enable"](gl["BLEND"])
				glm["blendFuncSeparate"](gl["SRC_ALPHA"], gl["ONE_MINUS_SRC_ALPHA"], gl["ONE"], gl["ONE_MINUS_SRC_ALPHA"])
			}
			else -> Unit
		}

		//glm["drawArrays"](type.glDrawMode, 0, 3)
		glm["drawElements"](type.glDrawMode, vertexCount, gl["UNSIGNED_SHORT"], offset)

		glm["activeTexture"](gl["TEXTURE0"])
		for (att in vertexLayout.attributes) {
			val loc = glm["getAttribLocation"](glProgram.program, att.name).toInt()
			if (loc >= 0) {
				glm["disableVertexAttribArray"](loc)
			}
		}
	}

	private fun glUniformMatrix4fv(location: Any, b: Boolean, values: FloatArray) {
		glm["uniformMatrix4fv"](location, b, values.asJsDynamic()["data"])
	}

	private fun glGetUniformLocation(glProgram: WebglProgram, name: String): Any? {
		return glm["getUniformLocation"](glProgram.program, name)
	}

	val tempTextures = arrayListOf<Texture>()

	override fun disposeTemporalPerFrameStuff() {
		for (tt in tempTextures) tt.close()
		tempTextures.clear()
	}

	override fun flipInternal() {
	}

	inner class WebglRenderBuffer() : RenderBuffer(this) {
		val wtex = tex as WebglTexture

		val renderbuffer = glm["createRenderbuffer"]()
		val framebuffer = glm["createFramebuffer"]()
		var oldViewport = IntArray(4)

		override fun start(width: Int, height: Int) {
			oldViewport = glm["getParameter"](gl["VIEWPORT"]).toIntArray()
			//println("oldViewport:${oldViewport.toList()}")
			glm["bindTexture"](gl["TEXTURE_2D"], wtex.tex)
			glm["texParameteri"](gl["TEXTURE_2D"], gl["TEXTURE_MAG_FILTER"], gl["LINEAR"])
			glm["texParameteri"](gl["TEXTURE_2D"], gl["TEXTURE_MIN_FILTER"], gl["LINEAR"])
			glm["texImage2D"](gl["TEXTURE_2D"], 0, gl["RGBA"], width, height, 0, gl["RGBA"], gl["UNSIGNED_BYTE"], null)
			glm["bindTexture"](gl["TEXTURE_2D"], null)
			glm["bindRenderbuffer"](gl["RENDERBUFFER"], renderbuffer)
			glm["bindFramebuffer"](gl["FRAMEBUFFER"], framebuffer)
			glm["framebufferTexture2D"](gl["FRAMEBUFFER"], gl["COLOR_ATTACHMENT0"], gl["TEXTURE_2D"], wtex.tex, 0)
			glm["renderbufferStorage"](gl["RENDERBUFFER"], gl["DEPTH_COMPONENT16"], width, height)
			glm["framebufferRenderbuffer"](gl["FRAMEBUFFER"], gl["DEPTH_ATTACHMENT"], gl["RENDERBUFFER"], renderbuffer)
			glm["viewport"](0, 0, width, height)
		}

		override fun end() {
			glm["flush"]()
			glm["bindTexture"](gl["TEXTURE_2D"], null)
			glm["bindRenderbuffer"](gl["RENDERBUFFER"], null)
			glm["bindFramebuffer"](gl["FRAMEBUFFER"], null)
			glm["viewport"](oldViewport[0], oldViewport[1], oldViewport[2], oldViewport[3])
		}

		override fun readBitmap(bmp: Bitmap32) {
			val data = UByteArray(bmp.area * 4)

			glm["readPixels"](0, 0, bmp.width, bmp.height, gl["RGBA"], gl["UNSIGNED_BYTE"], jsNew("Uint8Array", data.data.asJsDynamic()["data"]["buffer"]))

			val ibuffer = JTranscArrays.nativeReinterpretAsInt(data.data)
			for (n in 0 until bmp.area) bmp.data[n] = RGBA.rgbaToBgra(ibuffer[n])
		}

		override fun close() {
			glm["deleteFramebuffer"](framebuffer)
			glm["deleteRenderbuffer"](renderbuffer)
		}
	}

	override fun createRenderBuffer(): RenderBuffer = WebglRenderBuffer()
}

@JTranscMethodBody(target = "js", value = "return JA_I.fromTypedArray(new Int32Array(p0));")
external fun JsDynamic?.toIntArray(): IntArray