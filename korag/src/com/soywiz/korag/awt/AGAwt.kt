package com.soywiz.korag.awt

import com.jogamp.opengl.*
import com.jogamp.opengl.awt.GLCanvas
import com.jtransc.FastMemory
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
import com.soywiz.korio.util.Once
import java.io.Closeable
import java.nio.ByteBuffer

class AGFactoryAwt : AGFactory() {
	override val priority: Int = 500
	override fun create(): AG = AGAwt()
}

class AGAwt : AG() {
	var glprofile = GLProfile.getDefault()
	var glcapabilities = GLCapabilities(glprofile)
	val glcanvas = GLCanvas(glcapabilities)
	override val nativeComponent = glcanvas
	var initialized = false
	lateinit var ad: GLAutoDrawable
	lateinit var gl: GL2
	lateinit var glThread: Thread

	private fun setAutoDrawable(d: GLAutoDrawable) {
		glThread = Thread.currentThread()
		ad = d
		gl = d.gl as GL2
		initialized = true
	}

	override fun repaint() {
		glcanvas.repaint()
		//if (initialized) {
		//	onRender(this)
		//}
	}

	//val queue = LinkedList<(gl: GL) -> Unit>()

	init {
		glcanvas.addGLEventListener(object : GLEventListener {
			override fun reshape(d: GLAutoDrawable, x: Int, y: Int, width: Int, height: Int) {
				setAutoDrawable(d)
				backWidth = width
				backHeight = height
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
					onReady(this@AGAwt)
				}
				onRender(this@AGAwt)
				checkErrors { gl.glFlush() }

				//gl.glClearColor(1f, 1f, 0f, 1f)
				//gl.glClear(GL.GL_COLOR_BUFFER_BIT)
				//d.swapBuffers()
			}

			override fun init(d: GLAutoDrawable) {
				setAutoDrawable(d)
				//println("c")
			}

			override fun dispose(d: GLAutoDrawable) {
				setAutoDrawable(d)
				//println("d")
			}
		})
	}

	override fun createBuffer(kind: Buffer.Kind): Buffer = AwtBuffer(kind)

	override fun draw(vertices: Buffer, indices: Buffer, program: Program, type: DrawType, vertexLayout: VertexLayout, vertexCount: Int, offset: Int, blending: BlendMode, uniforms: Map<Uniform, Any>) {
		checkBuffers(vertices, indices)
		val glProgram = getProgram(program)
		(vertices as AwtBuffer).bind(gl)
		(indices as AwtBuffer).bind(gl)
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

		when (blending) {
			BlendMode.NONE -> {
				checkErrors { gl.glDisable(GL2.GL_BLEND) }
			}
			BlendMode.OVERLAY -> {
				checkErrors { gl.glEnable(GL2.GL_BLEND) }
				checkErrors { gl.glBlendFuncSeparate(GL2.GL_SRC_ALPHA, GL2.GL_ONE_MINUS_SRC_ALPHA, GL2.GL_ONE, GL2.GL_ONE_MINUS_SRC_ALPHA) }
			}
			else -> Unit
		}

		checkErrors { gl.glDrawElements(type.glDrawMode, vertexCount, GL2.GL_UNSIGNED_SHORT, offset.toLong()) }

		checkErrors { gl.glActiveTexture(GL2.GL_TEXTURE0) }
		for (att in vertexLayout.attributes) {
			val loc = checkErrors { gl.glGetAttribLocation(glProgram.id, att.name).toInt() }
			if (loc >= 0) {
				checkErrors { gl.glDisableVertexAttribArray(loc) }
			}
		}
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
		val id = checkErrors { gl.glCreateProgram() }
		val fragmentShaderId = createShader(GL2.GL_FRAGMENT_SHADER, program.fragment.toGlSlString())
		val vertexShaderId = createShader(GL2.GL_VERTEX_SHADER, program.vertex.toGlSlString())

		init {
			checkErrors { gl.glAttachShader(id, fragmentShaderId) }
			checkErrors { gl.glAttachShader(id, vertexShaderId) }
			checkErrors { gl.glLinkProgram(id) }
			val out = IntArray(1)
			checkErrors { gl.glGetProgramiv(id, GL2.GL_LINK_STATUS, out, 0) }
			//if (out[0] != GL2.GL_TRUE) {
			//	val ba = ByteArray(1024)
			//	val ia = intArrayOf(1024)
			//	gl.glGetProgramInfoLog(id, ba.size, ia, 0, ba, 0)
			//	//println(ia[0])
			//	val msg = ba.toString(Charsets.UTF_8)
			//	// gl.glGetShaderInfoLog()
			//	throw RuntimeException("Error Linking Program : '$msg' programId=$id")
			//}
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
			checkErrors { gl.glUseProgram(id) }
		}

		fun unuse() {
			checkErrors { gl.glUseProgram(0) }
		}

		override fun close() {
			checkErrors { gl.glDeleteShader(fragmentShaderId) }
			checkErrors { gl.glDeleteShader(vertexShaderId) }
			checkErrors { gl.glDeleteProgram(id) }
		}
	}

	override fun clear(color: Int, depth: Float, stencil: Int, clearColor: Boolean, clearDepth: Boolean, clearStencil: Boolean) {
		val r = RGBA.getRf(color)
		val g = RGBA.getGf(color)
		val b = RGBA.getBf(color)
		val a = RGBA.getAf(color)
		var bits = 0
		if (clearColor) bits = bits or GL.GL_COLOR_BUFFER_BIT
		if (clearDepth) bits = bits or GL.GL_DEPTH_BUFFER_BIT
		if (clearStencil) bits = bits or GL.GL_STENCIL_BUFFER_BIT
		checkErrors { gl.glClearColor(r, g, b, a) }
		checkErrors { gl.glClear(bits) }
	}

	override fun createTexture(): Texture = AwtTexture(this.gl)

	inner class AwtBuffer(kind: Buffer.Kind) : Buffer(kind) {
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
			if (id < 0) {
				val out = IntArray(1)
				checkErrors { gl.glGenBuffers(1, out, 0) }
				id = out[0]
			}
			if (dirty) {
				_bind(gl, id)
				checkErrors { gl.glBufferData(glKind, mem.length.toLong(), mem.byteBufferOrNull, GL.GL_STATIC_DRAW) }
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
		val texIds = IntArray(1)

		init {
			checkErrors { gl.glGenTextures(1, texIds, 0) }
		}

		val tex = texIds[0]

		init {
			println("Created texture with id: $tex")
		}

		override fun createMipmaps(): Boolean = true

		private var uploaded: Boolean = false
		private var actualBuffer: ByteBuffer? = null
		private var width: Int = 0
		private var height: Int = 0
		private var rgba: Boolean = false

		fun uploadBuffer(data: FastMemory, width: Int, height: Int, rgba: Boolean) {
			this.uploaded = true
			this.actualBuffer = clone(data.byteBufferOrNull)
			this.width = width
			this.height = height
			this.rgba = rgba
		}

		fun bindEnsuring() {
			bind()
			if (uploaded) {
				uploaded = false
				val Bpp = if (rgba) 4 else 1
				val type = if (rgba) GL2.GL_RGBA else GL2.GL_LUMINANCE
				checkErrors {
					gl.glTexImage2D(GL2.GL_TEXTURE_2D, 0, type, width, height, 0, type, GL2.GL_UNSIGNED_BYTE, actualBuffer)
				}
				if (mipmaps) {
					setFilter(true)
					setWrapST()
					gl.glGenerateMipmap(GL.GL_TEXTURE_2D)
				}
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

		override fun uploadBuffer(data: ByteBuffer, width: Int, height: Int, kind: Kind) {
			uploadBuffer(FastMemory.wrap(data), width, height, kind == Kind.RGBA)
		}

		override fun uploadBitmap32(bmp: Bitmap32) {
			val mem = FastMemory.alloc(bmp.area * 4)
			mem.setArrayInt32(0, bmp.data, 0, bmp.area)
			uploadBuffer(mem, bmp.width, bmp.height, true)
		}

		override fun uploadBitmap8(bmp: Bitmap8) {
			val mem = FastMemory.alloc(bmp.area)
			mem.setArrayInt8(0, bmp.data, 0, bmp.area)
			uploadBuffer(mem, bmp.width, bmp.height, false)
		}

		fun bind(): Unit = checkErrors { gl.glBindTexture(GL2.GL_TEXTURE_2D, tex) }
		fun unbind(): Unit = checkErrors { gl.glBindTexture(GL2.GL_TEXTURE_2D, 0) }

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

	inline fun <T> checkErrors(callback: () -> T): T {
		val res = callback()
		val error = gl.glGetError()
		if (error != GL.GL_NO_ERROR) {
			println("OpenGL error: $error")
			throw RuntimeException("OpenGL error: $error")
		}
		return res
	}
}