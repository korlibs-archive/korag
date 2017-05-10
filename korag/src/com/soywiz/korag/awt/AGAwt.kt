package com.soywiz.korag.awt

import com.jogamp.newt.opengl.GLWindow
import com.jogamp.opengl.*
import com.jogamp.opengl.awt.GLCanvas
import com.jtransc.FastMemory
import com.soywiz.korag.*
import com.soywiz.korag.geom.Matrix4
import com.soywiz.korag.shader.Program
import com.soywiz.korag.shader.Uniform
import com.soywiz.korag.shader.VarType
import com.soywiz.korag.shader.VertexLayout
import com.soywiz.korag.shader.gl.toGlSlString
import com.soywiz.korim.awt.AwtNativeImage
import com.soywiz.korim.bitmap.Bitmap
import com.soywiz.korim.bitmap.Bitmap32
import com.soywiz.korim.bitmap.Bitmap8
import com.soywiz.korim.bitmap.NativeImage
import com.soywiz.korim.color.RGBA
import com.soywiz.korio.error.invalidOp
import com.soywiz.korio.error.unsupported
import com.soywiz.korio.util.Once
import java.awt.event.*
import java.awt.image.DataBufferInt
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.IntBuffer

class AGFactoryAwt : AGFactory() {
	override val priority = 500
	override val supportsNativeFrame = true

	override fun create() = AGAwt()
	override fun createFastWindow(title: String, width: Int, height: Int): AGWindow {
		val glp = GLProfile.getDefault()
		val caps = GLCapabilities(glp)
		val window = GLWindow.create(caps)
		window.title = title
		window.setSize(width, height)
		window.isVisible = true
		window.addGLEventListener(object : GLEventListener {
			override fun reshape(drawable: GLAutoDrawable, x: Int, y: Int, width: Int, height: Int) = Unit
			override fun display(drawable: GLAutoDrawable) = Unit
			override fun init(drawable: GLAutoDrawable) = Unit
			override fun dispose(drawable: GLAutoDrawable) = Unit
		})

		return object : AGWindow() {
			override val agInput: AGInput = AGInput()
			//override val onResized: Signal<Unit> = Signal()

			override fun repaint() = Unit
			override val ag: AG = AGAwtNative(window)
		}
	}
}

abstract class AGAwtBase : AG() {
	var glprofile = GLProfile.getDefault()
	var glcapabilities = GLCapabilities(glprofile).apply {
		stencilBits = 8
	}
	var initialized = false
	lateinit var ad: GLAutoDrawable
	lateinit var gl: GL2
	lateinit var glThread: Thread

	override var pixelDensity: Double = 1.0

	protected fun setAutoDrawable(d: GLAutoDrawable) {
		glThread = Thread.currentThread()
		ad = d
		gl = d.gl as GL2
		initialized = true
	}

	val awtBase = this

	//val queue = LinkedList<(gl: GL) -> Unit>()

	override fun createBuffer(kind: Buffer.Kind): Buffer = AwtBuffer(kind)

	inner class AwtRenderBuffer : RenderBuffer() {
		var cachedVersion = -1
		val wtex get() = tex as AwtTexture

		val renderbuffer = IntBuffer.allocate(1)
		val framebuffer = IntBuffer.allocate(1)
		var oldViewport = IntArray(4)

		override fun start(width: Int, height: Int) {
			if (cachedVersion != contextVersion) {
				cachedVersion = contextVersion
				checkErrors { gl.glGenRenderbuffers(1, renderbuffer) }
				checkErrors { gl.glGenFramebuffers(1, framebuffer) }
			}

			checkErrors { gl.glGetIntegerv(GL.GL_VIEWPORT, oldViewport, 0) }
			checkErrors { gl.glBindTexture(GL.GL_TEXTURE_2D, wtex.tex) }
			checkErrors { gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR) }
			checkErrors { gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR) }
			checkErrors { gl.glTexImage2D(GL.GL_TEXTURE_2D, 0, GL.GL_RGBA, width, height, 0, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, null) }
			checkErrors { gl.glBindTexture(GL.GL_TEXTURE_2D, 0) }
			checkErrors { gl.glBindRenderbuffer(GL.GL_RENDERBUFFER, renderbuffer[0]) }
			checkErrors { gl.glBindFramebuffer(GL.GL_FRAMEBUFFER, framebuffer[0]) }
			checkErrors { gl.glFramebufferTexture2D(GL.GL_FRAMEBUFFER, GL.GL_COLOR_ATTACHMENT0, GL.GL_TEXTURE_2D, wtex.tex, 0) }
			checkErrors { gl.glRenderbufferStorage(GL.GL_RENDERBUFFER, GL.GL_DEPTH_COMPONENT16, width, height) }
			checkErrors { gl.glFramebufferRenderbuffer(GL.GL_FRAMEBUFFER, GL.GL_DEPTH_ATTACHMENT, GL.GL_RENDERBUFFER, renderbuffer[0]) }
			checkErrors { gl.glViewport(0, 0, width, height) }
		}

		override fun end() {
			//checkErrors { gl.glFlush() }
			checkErrors { gl.glBindTexture(GL.GL_TEXTURE_2D, 0) }
			checkErrors { gl.glBindRenderbuffer(GL.GL_RENDERBUFFER, 0) }
			checkErrors { gl.glBindFramebuffer(GL.GL_FRAMEBUFFER, 0) }
			checkErrors { gl.glViewport(oldViewport[0], oldViewport[1], oldViewport[2], oldViewport[3]) }
		}

		override fun readBitmap(bmp: Bitmap32) {
			checkErrors { gl.glReadPixels(0, 0, bmp.width, bmp.height, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, IntBuffer.wrap(bmp.data)) }

			//val ibuffer = JTranscArrays.nativeReinterpretAsInt(data.data)
			//for (n in 0 until bmp.area) bmp.data[n] = RGBA.rgbaToBgra(ibuffer[n])
		}

		override fun close() {
			checkErrors { gl.glDeleteFramebuffers(1, framebuffer) }
			checkErrors { gl.glDeleteRenderbuffers(1, renderbuffer) }
			framebuffer.put(0, 0)
			renderbuffer.put(0, 0)
		}
	}

	override fun createRenderBuffer(): RenderBuffer = AwtRenderBuffer()

	private fun BlendEquation.toGl() = when (this) {
		BlendEquation.ADD -> GL.GL_FUNC_ADD
		BlendEquation.SUBTRACT -> GL.GL_FUNC_SUBTRACT
		BlendEquation.REVERSE_SUBTRACT -> GL.GL_FUNC_REVERSE_SUBTRACT
	}

	private fun BlendFactor.toGl() = when (this) {
		BlendFactor.DESTINATION_ALPHA -> GL.GL_DST_ALPHA
		BlendFactor.DESTINATION_COLOR -> GL.GL_DST_COLOR
		BlendFactor.ONE -> GL.GL_ONE
		BlendFactor.ONE_MINUS_DESTINATION_ALPHA -> GL.GL_ONE_MINUS_DST_ALPHA
		BlendFactor.ONE_MINUS_DESTINATION_COLOR -> GL.GL_ONE_MINUS_DST_COLOR
		BlendFactor.ONE_MINUS_SOURCE_ALPHA -> GL.GL_ONE_MINUS_SRC_ALPHA
		BlendFactor.ONE_MINUS_SOURCE_COLOR -> GL.GL_ONE_MINUS_SRC_COLOR
		BlendFactor.SOURCE_ALPHA -> GL.GL_SRC_ALPHA
		BlendFactor.SOURCE_COLOR -> GL.GL_SRC_COLOR
		BlendFactor.ZERO -> GL.GL_ZERO
	}

	fun TriangleFace.toGl() = when (this) {
		TriangleFace.FRONT -> GL.GL_FRONT
		TriangleFace.BACK -> GL.GL_BACK
		TriangleFace.FRONT_AND_BACK -> GL.GL_FRONT_AND_BACK
		TriangleFace.NONE -> GL.GL_FRONT
	}

	fun CompareMode.toGl() = when (this) {
		CompareMode.ALWAYS -> GL.GL_ALWAYS
		CompareMode.EQUAL -> GL.GL_EQUAL
		CompareMode.GREATER -> GL.GL_GREATER
		CompareMode.GREATER_EQUAL -> GL.GL_GEQUAL
		CompareMode.LESS -> GL.GL_LESS
		CompareMode.LESS_EQUAL -> GL.GL_LEQUAL
		CompareMode.NEVER -> GL.GL_NEVER
		CompareMode.NOT_EQUAL -> GL.GL_NOTEQUAL
	}

	fun StencilOp.toGl() = when (this) {
		StencilOp.DECREMENT_SATURATE -> GL.GL_DECR
		StencilOp.DECREMENT_WRAP -> GL.GL_DECR_WRAP
		StencilOp.INCREMENT_SATURATE -> GL.GL_INCR
		StencilOp.INCREMENT_WRAP -> GL.GL_INCR_WRAP
		StencilOp.INVERT -> GL.GL_INVERT
		StencilOp.KEEP -> GL.GL_KEEP
		StencilOp.SET -> GL.GL_REPLACE
		StencilOp.ZERO -> GL.GL_ZERO
	}

	override fun draw(
		vertices: Buffer,
		program: Program,
		type: DrawType,
		vertexLayout: VertexLayout,
		vertexCount: Int,
		indices: Buffer?,
		offset: Int,
		blending: Blending,
		uniforms: Map<Uniform, Any>,
		stencil: StencilState,
		colorMask: ColorMaskState
	) {
		val mustFreeIndices = indices == null
		val aindices = indices ?: createIndexBuffer((0 until vertexCount).map(Int::toShort).toShortArray())
		checkBuffers(vertices, aindices)
		val glProgram = getProgram(program)
		(vertices as AwtBuffer).bind(gl)
		(aindices as AwtBuffer).bind(gl)
		glProgram.use()

		for (n in vertexLayout.attributePositions.indices) {
			val att = vertexLayout.attributes[n]
			val off = vertexLayout.attributePositions[n]
			val loc = checkErrors { gl.glGetAttribLocation(glProgram.id, att.name).toInt() }
			val glElementType = att.type.glElementType
			val elementCount = att.type.elementCount
			val totalSize = vertexLayout.totalSize
			if (loc >= 0) {
				checkErrors { gl.glEnableVertexAttribArray(loc) }
				checkErrors { gl.glVertexAttribPointer(loc, elementCount, glElementType, att.normalized, totalSize, off.toLong()) }
			}
		}
		var textureUnit = 0
		for ((uniform, value) in uniforms) {
			val location = checkErrors { gl.glGetUniformLocation(glProgram.id, uniform.name) }
			when (uniform.type) {
				VarType.TextureUnit -> {
					val unit = value as TextureUnit
					checkErrors { gl.glActiveTexture(GL2.GL_TEXTURE0 + textureUnit) }
					val tex = (unit.texture as AwtTexture?)
					tex?.bindEnsuring()
					tex?.setFilter(unit.linear)
					checkErrors { gl.glUniform1i(location, textureUnit) }
					textureUnit++
				}
				VarType.Mat4 -> {
					checkErrors { gl.glUniformMatrix4fv(location, 1, false, (value as Matrix4).data, 0) }
				}
				VarType.Float1 -> {
					checkErrors { gl.glUniform1f(location, (value as Number).toFloat()) }
				}
				else -> invalidOp("Don't know how to set uniform ${uniform.type}")
			}
		}

		if (blending.enabled) {
			checkErrors { gl.glEnable(GL2.GL_BLEND) }
			checkErrors { gl.glBlendEquationSeparate(blending.eqRGB.toGl(), blending.eqA.toGl()) }
			checkErrors { gl.glBlendFuncSeparate(blending.srcRGB.toGl(), blending.dstRGB.toGl(), blending.srcA.toGl(), blending.dstA.toGl()) }
		} else {
			checkErrors { gl.glDisable(GL2.GL_BLEND) }
		}

		checkErrors { gl.glColorMask(colorMask.red, colorMask.green, colorMask.blue, colorMask.alpha) }

		if (stencil.enabled) {
			checkErrors { gl.glEnable(GL2.GL_STENCIL_TEST) }
			checkErrors { gl.glStencilFunc(stencil.compareMode.toGl(), stencil.referenceValue, stencil.readMask) }
			checkErrors { gl.glStencilOp(stencil.actionOnDepthFail.toGl(), stencil.actionOnDepthPassStencilFail.toGl(), stencil.actionOnBothPass.toGl()) }
			checkErrors { gl.glStencilMask(stencil.writeMask) }
		} else {
			checkErrors { gl.glDisable(GL2.GL_STENCIL_TEST) }
			checkErrors { gl.glStencilMask(0) }
		}

		checkErrors { gl.glDrawElements(type.glDrawMode, vertexCount, GL2.GL_UNSIGNED_SHORT, offset.toLong()) }

		checkErrors { gl.glActiveTexture(GL2.GL_TEXTURE0) }
		for (att in vertexLayout.attributes) {
			val loc = checkErrors { gl.glGetAttribLocation(glProgram.id, att.name).toInt() }
			if (loc >= 0) {
				checkErrors { gl.glDisableVertexAttribArray(loc) }
			}
		}

		if (mustFreeIndices) aindices.close()
	}

	val DrawType.glDrawMode: Int get() = when (this) {
		DrawType.TRIANGLES -> GL2.GL_TRIANGLES
		DrawType.TRIANGLE_STRIP -> GL2.GL_TRIANGLE_STRIP
	}

	val VarType.glElementType: Int get() = when (this) {
		VarType.Int1 -> GL2.GL_INT
		VarType.Float1, VarType.Float2, VarType.Float3, VarType.Float4 -> GL2.GL_FLOAT
		VarType.Mat4 -> GL2.GL_FLOAT
		VarType.Bool1 -> GL2.GL_UNSIGNED_BYTE
		VarType.Byte4 -> GL2.GL_UNSIGNED_BYTE
		VarType.TextureUnit -> GL2.GL_INT
	}

	private val programs = hashMapOf<Program, AwtProgram>()
	fun getProgram(program: Program): AwtProgram = programs.getOrPut(program) { AwtProgram(gl, program) }

	inner class AwtProgram(val gl: GL2, val program: Program) : Closeable {
		var cachedVersion = -1
		var id: Int = 0
		var fragmentShaderId: Int = 0
		var vertexShaderId: Int = 0

		private fun ensure() {
			if (cachedVersion != contextVersion) {
				cachedVersion = contextVersion
				id = checkErrors { gl.glCreateProgram() }
				fragmentShaderId = createShader(GL2.GL_FRAGMENT_SHADER, program.fragment.toGlSlString())
				vertexShaderId = createShader(GL2.GL_VERTEX_SHADER, program.vertex.toGlSlString())
				checkErrors { gl.glAttachShader(id, fragmentShaderId) }
				checkErrors { gl.glAttachShader(id, vertexShaderId) }
				checkErrors { gl.glLinkProgram(id) }
				val out = IntArray(1)
				checkErrors { gl.glGetProgramiv(id, GL2.GL_LINK_STATUS, out, 0) }
			}
		}

		fun createShader(type: Int, str: String): Int {
			val shaderId = checkErrors { gl.glCreateShader(type) }
			checkErrors { gl.glShaderSource(shaderId, 1, arrayOf(str), intArrayOf(str.length), 0) }
			checkErrors { gl.glCompileShader(shaderId) }

			val out = IntArray(1)
			checkErrors { gl.glGetShaderiv(shaderId, GL2.GL_COMPILE_STATUS, out, 0) }
			if (out[0] != GL2.GL_TRUE) {
				// gl.glGetShaderInfoLog()
				throw RuntimeException("Error Compiling Shader")
			}
			return shaderId
		}

		fun use() {
			ensure()
			checkErrors { gl.glUseProgram(id) }
		}

		fun unuse() {
			ensure()
			checkErrors { gl.glUseProgram(0) }
		}

		override fun close() {
			checkErrors { gl.glDeleteShader(fragmentShaderId) }
			checkErrors { gl.glDeleteShader(vertexShaderId) }
			checkErrors { gl.glDeleteProgram(id) }
		}
	}

	override fun clear(color: Int, depth: Float, stencil: Int, clearColor: Boolean, clearDepth: Boolean, clearStencil: Boolean) {
		//println("CLEAR: $color, $depth")
		var bits = 0
		checkErrors { gl.glDisable(GL.GL_SCISSOR_TEST) }
		if (clearColor) {
			bits = bits or GL.GL_COLOR_BUFFER_BIT
			checkErrors { gl.glClearColor(RGBA.getRf(color), RGBA.getGf(color), RGBA.getBf(color), RGBA.getAf(color)) }
		}
		if (clearDepth) {
			bits = bits or GL.GL_DEPTH_BUFFER_BIT
			checkErrors { gl.glClearDepth(depth.toDouble()) }
		}
		if (clearStencil) {
			bits = bits or GL.GL_STENCIL_BUFFER_BIT
			checkErrors { gl.glStencilMask(-1) }
			checkErrors { gl.glClearStencil(stencil) }
		}
		checkErrors { gl.glClear(bits) }
	}

	override fun createTexture(): Texture = AwtTexture(this.gl)

	inner class AwtBuffer(kind: Buffer.Kind) : Buffer(kind) {
		var cachedVersion = -1
		private var id = -1
		val glKind = if (kind == Buffer.Kind.INDEX) GL.GL_ELEMENT_ARRAY_BUFFER else GL.GL_ARRAY_BUFFER

		override fun afterSetMem() {
		}

		override fun close() {
			val deleteId = id
			checkErrors { gl.glDeleteBuffers(1, intArrayOf(deleteId), 0) }
			id = -1
		}

		fun getGlId(gl: GL2): Int {
			if (cachedVersion != contextVersion) {
				cachedVersion = contextVersion
				id = -1
			}
			if (id < 0) {
				val out = IntArray(1)
				checkErrors { gl.glGenBuffers(1, out, 0) }
				id = out[0]
			}
			if (dirty) {
				_bind(gl, id)
				if (mem != null) {
					val bb = mem!!.byteBufferOrNull
					val old = bb.position()
					bb.position(memOffset)
					checkErrors { gl.glBufferData(glKind, memLength.toLong(), bb, GL.GL_STATIC_DRAW) }
					bb.position(old)
				}
			}
			return id
		}

		fun _bind(gl: GL2, id: Int) {
			checkErrors { gl.glBindBuffer(glKind, id) }
		}

		fun bind(gl: GL2) {
			_bind(gl, getGlId(gl))
		}
	}

	inner class AwtTexture(val gl: GL2) : Texture() {
		var cachedVersion = -1
		val texIds = IntArray(1)

		val tex: Int get() {
			if (cachedVersion != contextVersion) {
				cachedVersion = contextVersion
				invalidate()
				checkErrors { gl.glGenTextures(1, texIds, 0) }
			}
			return texIds[0]
		}

		fun createBufferForBitmap(bmp: Bitmap?): ByteBuffer? {
			return when (bmp) {
				null -> null
				is NativeImage -> {
					val mem = FastMemory.alloc(bmp.area * 4)
					val image = bmp as AwtNativeImage
					val data = (image.awtImage.raster.dataBuffer as DataBufferInt).data
					//println("BMP: ${image.awtImage.type}")
					for (n in 0 until bmp.area) {
						mem.setAlignedInt32(n, RGBA.rgbaToBgra(data[n]))
					}
					//mem.setArrayInt32(0, data, 0, bmp.area)
					return mem.byteBufferOrNull
				}
				is Bitmap8 -> {
					val mem = FastMemory.alloc(bmp.area)
					mem.setArrayInt8(0, bmp.data, 0, bmp.area)
					return mem.byteBufferOrNull
				}
				is Bitmap32 -> {
					val abmp: Bitmap32 = if (premultiplied) bmp.premultipliedIfRequired() else bmp.depremultipliedIfRequired()
					//println("BMP: Bitmap32")
					//val abmp: Bitmap32 = bmp
					val mem = FastMemory.alloc(abmp.area * 4)
					mem.setArrayInt32(0, abmp.data, 0, abmp.area)
					return mem.byteBufferOrNull
				}
				else -> unsupported()
			}
		}

		override fun actualSyncUpload(source: BitmapSourceBase, bmp: Bitmap?, requestMipmaps: Boolean) {
			val Bpp = if (source.rgba) 4 else 1
			val type = if (source.rgba) {
				//if (source is NativeImage) GL2.GL_BGRA else GL2.GL_RGBA
				GL2.GL_RGBA
			} else {
				GL2.GL_LUMINANCE
			}

			val buffer = createBufferForBitmap(bmp)
			if (buffer != null) {
				checkErrors {
					gl.glTexImage2D(GL2.GL_TEXTURE_2D, 0, type, source.width, source.height, 0, type, GL2.GL_UNSIGNED_BYTE, buffer)
				}
			}
			//println(buffer)

			this.mipmaps = false

			if (requestMipmaps) {
				//println(" - mipmaps")
				this.mipmaps = true
				bind()
				setFilter(true)
				setWrapST()
				checkErrors {
					gl.glGenerateMipmap(GL.GL_TEXTURE_2D)
				}
			} else {
				//println(" - nomipmaps")
			}
		}

		fun clone(original: ByteBuffer): ByteBuffer {
			val clone = ByteBuffer.allocate(original.capacity())
			original.rewind()//copy from the beginning
			clone.put(original)
			original.rewind()
			clone.flip()
			return clone
		}

		override fun bind(): Unit = checkErrors { gl.glBindTexture(GL2.GL_TEXTURE_2D, tex) }
		override fun unbind(): Unit = checkErrors { gl.glBindTexture(GL2.GL_TEXTURE_2D, 0) }

		override fun close(): Unit = checkErrors { gl.glDeleteTextures(1, texIds, 0) }

		fun setFilter(linear: Boolean) {
			val minFilter = if (this.mipmaps) {
				if (linear) GL2.GL_LINEAR_MIPMAP_NEAREST else GL2.GL_NEAREST_MIPMAP_NEAREST
			} else {
				if (linear) GL2.GL_LINEAR else GL2.GL_NEAREST
			}
			val magFilter = if (linear) GL2.GL_LINEAR else GL2.GL_NEAREST

			setWrapST()
			setMinMag(minFilter, magFilter)
		}

		private fun setWrapST() {
			checkErrors { gl.glTexParameteri(GL2.GL_TEXTURE_2D, GL2.GL_TEXTURE_WRAP_S, GL2.GL_CLAMP_TO_EDGE) }
			checkErrors { gl.glTexParameteri(GL2.GL_TEXTURE_2D, GL2.GL_TEXTURE_WRAP_T, GL2.GL_CLAMP_TO_EDGE) }
		}

		private fun setMinMag(min: Int, mag: Int) {
			checkErrors { gl.glTexParameteri(GL2.GL_TEXTURE_2D, GL2.GL_TEXTURE_MIN_FILTER, min) }
			checkErrors { gl.glTexParameteri(GL2.GL_TEXTURE_2D, GL2.GL_TEXTURE_MAG_FILTER, mag) }
		}
	}

	@PublishedApi internal val checkErrors = true

	inline fun <T> checkErrors(callback: () -> T): T {
		val res = callback()
		if (checkErrors) {
			val error = gl.glGetError()
			if (error != GL.GL_NO_ERROR) {
				System.err.println("OpenGL error: $error")
				//throw RuntimeException("OpenGL error: $error")
			}
		}
		return res
	}
}

class AGAwt : AGAwtBase(), AGContainer {
	val glcanvas = GLCanvas(glcapabilities)
	override val nativeComponent = glcanvas

	override val ag: AG = this

	override val agInput: AGInput = AGInput()

	/*
	override var mouseX: Int = 0
	override var mouseY: Int = 0
	override val onMouseOver: Signal<Unit> = Signal()
	override val onMouseUp: Signal<Unit> = Signal()
	override val onMouseDown: Signal<Unit> = Signal()
	*/

	override fun dispose() {
		glcanvas.removeMouseListener(mouseEventListener)
		glcanvas.removeMouseMotionListener(mouseEventListener)
		glcanvas.removeKeyListener(keyListener)
		glcanvas.disposeGLEventListener(glEventListener, true)
	}

	override fun repaint() {
		glcanvas.repaint()
		//if (initialized) {
		//	onRender(this)
		//}
	}

	override fun resized() {
		onResized(Unit)
	}

	private fun updateMouse(e: MouseEvent) {
		this.agInput.mouseEvent.x = e.x
		this.agInput.mouseEvent.y = e.y
	}

	private fun updateKey(e: KeyEvent) {
		this.agInput.keyEvent.keyCode = e.keyCode
	}

	val glEventListener = object : GLEventListener {
		override fun reshape(d: GLAutoDrawable, x: Int, y: Int, width: Int, height: Int) {
			setAutoDrawable(d)

			val scaleX = glcanvas.width.toDouble() / glcanvas.surfaceWidth.toDouble()
			val scaleY = glcanvas.height.toDouble() / glcanvas.surfaceHeight.toDouble()

			pixelDensity = (scaleX + scaleY) * 0.5

			//println("scale($scaleX, $scaleY)")

			backWidth = (width * pixelDensity).toInt()
			backHeight = (height * pixelDensity).toInt()
			//d.gl.glViewport(0, 0, width, height)
			resized()
			//println("a")
		}

		var onReadyOnce = Once()

		override fun display(d: GLAutoDrawable) {
			setAutoDrawable(d)

			//while (true) {
			//	val callback = synchronized(queue) { if (queue.isNotEmpty()) queue.remove() else null } ?: break
			//	callback(gl)
			//}

			onReadyOnce {
				ready()
			}
			onRender(awtBase)
			checkErrors { gl.glFlush() }

			//gl.glClearColor(1f, 1f, 0f, 1f)
			//gl.glClear(GL.GL_COLOR_BUFFER_BIT)
			//d.swapBuffers()
		}

		override fun init(d: GLAutoDrawable) {
			contextVersion++
			setAutoDrawable(d)
			//println("c")
		}

		override fun dispose(d: GLAutoDrawable) {
			setAutoDrawable(d)
			//println("d")
		}
	}

	val mouseMotionEventListener = object : MouseMotionAdapter() {
		override fun mouseMoved(e: MouseEvent) {
			updateMouse(e)
			agInput.onMouseOver(agInput.mouseEvent)
		}

		override fun mouseDragged(e: MouseEvent) {
			updateMouse(e)
			agInput.onMouseOver(agInput.mouseEvent)
		}
	}

	val mouseEventListener = object : MouseAdapter() {
		override fun mouseReleased(e: MouseEvent) {
			updateMouse(e)
			agInput.onMouseUp(agInput.mouseEvent)
		}

		override fun mousePressed(e: MouseEvent) {
			updateMouse(e)
			agInput.onMouseDown(agInput.mouseEvent)
		}

		override fun mouseClicked(e: MouseEvent) {
			updateMouse(e)
			agInput.onMouseClick(agInput.mouseEvent)
		}
	}

	val keyListener = object : KeyAdapter() {
		override fun keyTyped(e: KeyEvent) {
			updateKey(e)
			agInput.onKeyTyped(agInput.keyEvent)
		}

		override fun keyPressed(e: KeyEvent) {
			updateKey(e)
			agInput.onKeyDown(agInput.keyEvent)
		}

		override fun keyReleased(e: KeyEvent) {
			updateKey(e)
			agInput.onKeyUp(agInput.keyEvent)
		}
	}

	init {
		//((glcanvas as JoglNewtAwtCanvas).getNativeWindow() as JAWTWindow).setSurfaceScale(new float[] {2, 2});
		//glcanvas.nativeSurface.
		//println(glcanvas.nativeSurface.convertToPixelUnits(intArrayOf(1000)).toList())

		glcanvas.addMouseMotionListener(mouseMotionEventListener)
		glcanvas.addMouseListener(mouseEventListener)
		glcanvas.addGLEventListener(glEventListener)
		glcanvas.addKeyListener(keyListener)
	}
}

class AGAwtNative(override val nativeComponent: Any) : AGAwtBase() {

}