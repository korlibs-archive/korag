package com.soywiz.korag.webgl

import com.jtransc.JTranscArrays
import com.jtransc.annotation.JTranscMethodBody
import com.jtransc.io.JTranscConsole
import com.jtransc.js.*
import com.soywiz.korag.AG
import com.soywiz.korag.AGFactory
import com.soywiz.korag.geom.Matrix4
import com.soywiz.korag.shader.Program
import com.soywiz.korag.shader.Uniform
import com.soywiz.korag.shader.VarType
import com.soywiz.korag.shader.VertexLayout
import com.soywiz.korag.shader.gl.toGlSlString
import com.soywiz.korim.bitmap.Bitmap
import com.soywiz.korim.bitmap.Bitmap32
import com.soywiz.korim.bitmap.Bitmap8
import com.soywiz.korim.bitmap.NativeImage
import com.soywiz.korim.color.RGBA
import com.soywiz.korim.html.CanvasNativeImage
import com.soywiz.korio.error.invalidOp
import com.soywiz.korio.util.OS
import com.soywiz.korio.util.Once
import com.soywiz.korio.util.UByteArray
import java.io.Closeable
import java.nio.ByteBuffer

class AGFactoryWebgl : AGFactory() {
	override val available: Boolean = OS.isJs
	override val priority: Int = 1500
	override fun create(): AG = AGWebgl()
}

class AGWebgl : AG() {
	val canvas = document.call("createElement", "canvas")!!
	val glOpts = jsObject("premultipliedAlpha" to false)
	val gl = canvas.call("getContext", "webgl", glOpts) ?: canvas.call("getContext", "experimental-webgl", glOpts)
	override val nativeComponent: Any = canvas
	override val pixelDensity: Double get() = window["devicePixelRatio"]?.toDouble() ?: 1.0
	val onReadyOnce = Once()

	override fun repaint() {
		onReadyOnce { ready() }
		onRender(this)
	}

	override fun resized() {
		backWidth = canvas["width"].toInt()
		backHeight = canvas["height"].toInt()
		gl.call("viewport", 0, 0, backWidth, backHeight)
		onResized(Unit)
	}

	override fun dispose() {
		// https://www.khronos.org/webgl/wiki/HandlingContextLost
		// https://gist.github.com/mattdesl/9995467
	}

	override fun clear(color: Int, depth: Float, stencil: Int, clearColor: Boolean, clearDepth: Boolean, clearStencil: Boolean) {
		gl.call("clearColor", RGBA.getRf(color), RGBA.getGf(color), RGBA.getBf(color), RGBA.getAf(color))
		gl.call("clearDepth", depth)
		gl.call("clearStencil", stencil)
		var flags = 0
		if (clearColor) flags = flags or gl["COLOR_BUFFER_BIT"].toInt()
		if (clearDepth) flags = flags or gl["DEPTH_BUFFER_BIT"].toInt()
		if (clearStencil) flags = flags or gl["STENCIL_BUFFER_BIT"].toInt()
		gl.call("clear", flags)
	}

	inner class WebglProgram(val p: Program) : Closeable {
		var program = gl.call("createProgram")

		fun createShader(type: JsDynamic?, source: String): JsDynamic? {
			val shader = gl.call("createShader", type)
			gl.call("shaderSource", shader, source)
			gl.call("compileShader", shader)

			val success = gl.call("getShaderParameter", shader, gl["COMPILE_STATUS"]).toBool()
			if (!success) {
				val error = gl.call("getShaderInfoLog", shader).toJavaStringOrNull()
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
			gl.call("attachShader", program, vertex)
			gl.call("attachShader", program, fragment)

			gl.call("linkProgram", program)

			if (!gl.call("getProgramParameter", program, gl["LINK_STATUS"]).toBool()) {
				val info = gl.call("getProgramInfoLog", program)
				JTranscConsole.error("Could not compile WebGL program: " + info)
			}
		}


		fun bind() {
			gl.call("useProgram", this.program)
		}

		fun unbind() {
			gl.call("userProgram", null)
		}

		override fun close() {
			gl.call("deleteShader", this.vertex)
			gl.call("deleteShader", this.fragment)
			gl.call("deleteProgram", this.program)
		}
	}

	inner class WebglTexture() : Texture() {
		val tex = gl.call("createTexture")

		override fun createMipmaps(): Boolean {
			bind()
			setFilter(true)
			setWrapST()
			//gl["generateMipmap"](gl["TEXTURE_2D"])
			return false
		}

		override fun actualSyncUpload(source: BitmapSourceBase, bmp: Bitmap?) {
			when (bmp) {
				null -> {
				}
				is CanvasNativeImage -> {
					val type = gl["RGBA"]
					//println("Uploading native image!")
					gl.call("texImage2D", gl["TEXTURE_2D"], 0, type, type, gl["UNSIGNED_BYTE"], bmp.canvas)
				}
				is Bitmap32, is Bitmap8 -> {
					val width = bmp.width
					val height = bmp.height
					val rgba = bmp is Bitmap32
					val Bpp = if (rgba) 4 else 1
					val data: Any = (bmp as? Bitmap32)?.data ?: ((bmp as? Bitmap8)?.data ?: ByteArray(width * height * Bpp))
					val rdata = jsNew("Uint8Array", data.asJsDynamic().call("getBuffer"), 0, width * height * Bpp)
					val type = if (rgba) gl["RGBA"] else gl["LUMINANCE"]
					gl.call("texImage2D", gl["TEXTURE_2D"], 0, type, width, height, 0, type, gl["UNSIGNED_BYTE"], rdata)
				}
			}
		}

		override fun bind(): Unit = run { gl.call("bindTexture", gl["TEXTURE_2D"], tex) }
		override fun unbind(): Unit = run { gl.call("bindTexture", gl["TEXTURE_2D"], null) }

		override fun close(): Unit = run { gl.call("deleteTexture", tex) }

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
			gl.call("texParameteri", gl["TEXTURE_2D"], gl["TEXTURE_WRAP_S"], gl["CLAMP_TO_EDGE"])
			gl.call("texParameteri", gl["TEXTURE_2D"], gl["TEXTURE_WRAP_T"], gl["CLAMP_TO_EDGE"])
		}

		private fun setMinMag(min: Int, mag: Int) {
			gl.call("texParameteri", gl["TEXTURE_2D"], gl["TEXTURE_MIN_FILTER"], min)
			gl.call("texParameteri", gl["TEXTURE_2D"], gl["TEXTURE_MAG_FILTER"], mag)
		}
	}

	inner class WebglBuffer(kind: Kind) : Buffer(kind) {
		val buffer = gl.call("createBuffer")
		val target = if (kind == Kind.INDEX) gl["ELEMENT_ARRAY_BUFFER"] else gl["ARRAY_BUFFER"]

		fun bind() {
			gl.call("bindBuffer", this.target, this.buffer)
		}

		override fun afterSetMem() {
			bind()
			if (mem != null) {
				val buffer = mem.asJsDynamic()["buffer"]
				val typedArray = jsNew("Int8Array", buffer, memOffset, memLength)
				//console.methods["log"](target)
				//console.methods["log"](typedArray)
				gl.call("bufferData", this.target, typedArray, gl["STATIC_DRAW"])
			}
		}

		override fun close() {
			gl.call("deleteBuffer", buffer)
		}
	}

	override fun createTexture(): Texture = WebglTexture()
	override fun createBuffer(kind: Buffer.Kind): Buffer = WebglBuffer(kind)

	private val programs = hashMapOf<String, WebglProgram>()

	fun getProgram(program: Program): WebglProgram = programs.getOrPut(program.name) { WebglProgram(program) }

	val VarType.webglElementType: Int get() = when (this) {
		VarType.Int1 -> gl["INT"].toInt()
		VarType.Float1, VarType.Float2, VarType.Float3, VarType.Float4 -> gl["FLOAT"].toInt()
		VarType.Mat4 -> gl["FLOAT"].toInt()
		VarType.Bool1 -> gl["UNSIGNED_BYTE"].toInt()
		VarType.Byte4 -> gl["UNSIGNED_BYTE"].toInt()
		VarType.TextureUnit -> gl["INT"].toInt()
	}

	val DrawType.glDrawMode: Int get() = when (this) {
		DrawType.TRIANGLES -> gl["TRIANGLES"].toInt()
		DrawType.TRIANGLE_STRIP -> gl["TRIANGLE_STRIP"].toInt()
	}

	private fun BlendFactor.toGl(): Int = when (this) {
		BlendFactor.DESTINATION_ALPHA -> gl["DST_ALPHA"].toInt()
		BlendFactor.DESTINATION_COLOR -> gl["DST_COLOR"].toInt()
		BlendFactor.ONE -> gl["ONE"].toInt()
		BlendFactor.ONE_MINUS_DESTINATION_ALPHA -> gl["ONE_MINUS_DST_ALPHA"].toInt()
		BlendFactor.ONE_MINUS_DESTINATION_COLOR -> gl["ONE_MINUS_DST_COLOR"].toInt()
		BlendFactor.ONE_MINUS_SOURCE_ALPHA -> gl["ONE_MINUS_SRC_ALPHA"].toInt()
		BlendFactor.ONE_MINUS_SOURCE_COLOR -> gl["ONE_MINUS_SRC_COLOR"].toInt()
		BlendFactor.SOURCE_ALPHA -> gl["SRC_ALPHA"].toInt()
		BlendFactor.SOURCE_COLOR -> gl["SRC_COLOR"].toInt()
		BlendFactor.ZERO -> gl["ZERO"].toInt()
	}

	override fun draw(vertices: Buffer, indices: Buffer, program: Program, type: DrawType, vertexLayout: VertexLayout, vertexCount: Int, offset: Int, blending: BlendFactors, uniforms: Map<Uniform, Any>) {
		checkBuffers(vertices, indices)
		val glProgram = getProgram(program)
		(vertices as WebglBuffer).bind()
		(indices as WebglBuffer).bind()
		glProgram.bind()

		for (n in vertexLayout.attributePositions.indices) {
			val att = vertexLayout.attributes[n]
			val off = vertexLayout.attributePositions[n]
			val loc = gl.call("getAttribLocation", glProgram.program, att.name).toInt()
			val glElementType = att.type.webglElementType
			val elementCount = att.type.elementCount
			val totalSize = vertexLayout.totalSize
			if (loc >= 0) {
				gl.call("enableVertexAttribArray", loc)
				gl.call("vertexAttribPointer", loc, elementCount, glElementType, att.normalized, totalSize, off)
			}
		}
		var textureUnit = 0
		for ((uniform, value) in uniforms) {
			val location = glGetUniformLocation(glProgram, uniform.name) ?: continue
			when (uniform.type) {
				VarType.TextureUnit -> {
					val unit = value as TextureUnit
					gl.call("activeTexture", gl["TEXTURE0"].toInt() + textureUnit)
					val tex = (unit.texture as WebglTexture?)
					tex?.bindEnsuring()
					tex?.setFilter(unit.linear)
					gl.call("uniform1i", location, textureUnit)
					textureUnit++
				}
				VarType.Mat4 -> {
					glUniformMatrix4fv(location, false, (value as Matrix4).data)
				}
				VarType.Float1 -> {
					gl.call("uniform1f", location, (value as Number).toFloat())
				}
				else -> invalidOp("Don't know how to set uniform ${uniform.type}")
			}
		}

		if (blending.disabled) {
			gl.call("disable", gl["BLEND"])
		} else {
			gl.call("enable", gl["BLEND"])
			gl.call("blendFuncSeparate", blending.srcRGB.toGl(), blending.dstRGB.toGl(), blending.srcA.toGl(), blending.dstA.toGl())
		}

		//gl["drawArrays"](type.glDrawMode, 0, 3)
		gl.call("drawElements", type.glDrawMode, vertexCount, gl["UNSIGNED_SHORT"], offset)

		gl.call("activeTexture", gl["TEXTURE0"])
		for (att in vertexLayout.attributes) {
			val loc = gl.call("getAttribLocation", glProgram.program, att.name).toInt()
			if (loc >= 0) {
				gl.call("disableVertexAttribArray", loc)
			}
		}
	}

	private fun glUniformMatrix4fv(location: Any, b: Boolean, values: FloatArray) {
		gl.call("uniformMatrix4fv", location, b, values.asJsDynamic()["data"])
	}

	private fun glGetUniformLocation(glProgram: WebglProgram, name: String): Any? {
		return gl.call("getUniformLocation", glProgram.program, name)
	}

	val tempTextures = arrayListOf<Texture>()

	override fun disposeTemporalPerFrameStuff() {
		for (tt in tempTextures) tt.close()
		tempTextures.clear()
	}

	override fun flipInternal() {
	}

	inner class WebglRenderBuffer() : RenderBuffer() {
		val wtex = tex as WebglTexture

		val renderbuffer = gl.call("createRenderbuffer")
		val framebuffer = gl.call("createFramebuffer")
		var oldViewport = IntArray(4)

		override fun start(width: Int, height: Int) {
			oldViewport = gl.call("getParameter", gl["VIEWPORT"]).toIntArray()
			//println("oldViewport:${oldViewport.toList()}")
			gl.call("bindTexture", gl["TEXTURE_2D"], wtex.tex)
			gl.call("texParameteri", gl["TEXTURE_2D"], gl["TEXTURE_MAG_FILTER"], gl["LINEAR"])
			gl.call("texParameteri", gl["TEXTURE_2D"], gl["TEXTURE_MIN_FILTER"], gl["LINEAR"])
			gl.call("texImage2D", gl["TEXTURE_2D"], 0, gl["RGBA"], width, height, 0, gl["RGBA"], gl["UNSIGNED_BYTE"], null)
			gl.call("bindTexture", gl["TEXTURE_2D"], null)
			gl.call("bindRenderbuffer", gl["RENDERBUFFER"], renderbuffer)
			gl.call("bindFramebuffer", gl["FRAMEBUFFER"], framebuffer)
			gl.call("framebufferTexture2D", gl["FRAMEBUFFER"], gl["COLOR_ATTACHMENT0"], gl["TEXTURE_2D"], wtex.tex, 0)
			gl.call("renderbufferStorage", gl["RENDERBUFFER"], gl["DEPTH_COMPONENT16"], width, height)
			gl.call("framebufferRenderbuffer", gl["FRAMEBUFFER"], gl["DEPTH_ATTACHMENT"], gl["RENDERBUFFER"], renderbuffer)
			gl.call("viewport", 0, 0, width, height)
		}

		override fun end() {
			gl.call("flush")
			gl.call("bindTexture", gl["TEXTURE_2D"], null)
			gl.call("bindRenderbuffer", gl["RENDERBUFFER"], null)
			gl.call("bindFramebuffer", gl["FRAMEBUFFER"], null)
			gl.call("viewport", oldViewport[0], oldViewport[1], oldViewport[2], oldViewport[3])
		}

		override fun readBitmap(bmp: Bitmap32) {
			val data = UByteArray(bmp.area * 4)

			gl.call("readPixels", 0, 0, bmp.width, bmp.height, gl["RGBA"], gl["UNSIGNED_BYTE"], jsNew("Uint8Array", data.data.asJsDynamic()["data"]["buffer"]))

			val ibuffer = JTranscArrays.nativeReinterpretAsInt(data.data)
			for (n in 0 until bmp.area) bmp.data[n] = RGBA.rgbaToBgra(ibuffer[n])
		}

		override fun close() {
			gl.call("deleteFramebuffer", framebuffer)
			gl.call("deleteRenderbuffer", renderbuffer)
		}
	}

	override fun createRenderBuffer(): RenderBuffer = WebglRenderBuffer()
}

@JTranscMethodBody(target = "js", value = "return JA_I.fromTypedArray(new Int32Array(p0));")
external fun JsDynamic?.toIntArray(): IntArray