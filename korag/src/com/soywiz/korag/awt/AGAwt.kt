package com.soywiz.korag.awt

import com.jogamp.opengl.*
import com.jogamp.opengl.awt.GLCanvas
import com.jtransc.js.get
import com.jtransc.js.toInt
import com.soywiz.korag.AG
import com.soywiz.korag.AGFactory
import com.soywiz.korag.BlendMode
import com.soywiz.korag.geom.Matrix4
import com.soywiz.korag.shader.*
import com.soywiz.korag.webgl.AGWebgl
import com.soywiz.korim.color.RGBA
import com.soywiz.korio.error.invalidOp
import java.io.Closeable


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
			override fun reshape(d: GLAutoDrawable, p1: Int, p2: Int, p3: Int, p4: Int) {
				setAutoDrawable(d)
				//println("a")
			}

			override fun display(d: GLAutoDrawable) {
				setAutoDrawable(d)

				//while (true) {
				//	val callback = synchronized(queue) { if (queue.isNotEmpty()) queue.remove() else null } ?: break
				//	callback(gl)
				//}

				onRender(this@AGAwt)
				gl.glFlush()

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

	//fun executeInGlThread(callback: (gl: GL) -> Unit) {
	//	if (glThread == Thread.currentThread()) {
	//		// @TODO:
	//		callback(this.gl)
	//	}else {
	//		synchronized(queue) { queue += callback }
	//	}
	//}

	override fun createBuffer(kind: Buffer.Kind): Buffer = AwtBuffer(kind)

	override fun draw(vertices: Buffer, indices: Buffer, program: Program, type: DrawType, vertexFormat: VertexFormat, vertexCount: Int, offset: Int, blending: BlendMode, uniforms: Map<Uniform, Any>) {
		checkBuffers(vertices, indices)
		val glProgram = getProgram(program)
		(vertices as AwtBuffer).bind(gl)
		(indices as AwtBuffer).bind(gl)
		glProgram.use()

		for (n in vertexFormat.attributePositions.indices) {
			val att = vertexFormat.attributes[n]
			val off = vertexFormat.attributePositions[n]
			val loc = gl.glGetAttribLocation(glProgram.id, att.name).toInt()
			val glElementType = att.type.glElementType
			val elementCount = att.type.elementCount
			val totalSize = vertexFormat.totalSize
			if (loc >= 0) {
				gl.glEnableVertexAttribArray(loc)
				gl.glVertexAttribPointer(loc, elementCount, glElementType, att.normalized, totalSize, off.toLong())
			}
		}
		var textureUnit = 0
		for ((uniform, value) in uniforms) {
			val location = gl.glGetUniformLocation(glProgram.id, uniform.name) ?: continue
			when (uniform.type) {
				VarType.TextureUnit -> {
					val unit = value as TextureUnit
					gl.glActiveTexture(GL2.GL_TEXTURE0 + textureUnit)
					val tex = (unit.texture as AGWebgl.WebglTexture?)
					tex?.bind()
					tex?.setFilter(unit.linear)
					gl.glUniform1i(location, textureUnit)
					textureUnit++
				}
				VarType.Mat4 -> {
					gl.glUniformMatrix4fv(location, 1, false, (value as Matrix4).data, 0)
				}
				VarType.Float1 -> {
					gl.glUniform1f(location, (value as Number).toFloat())
				}
				else -> invalidOp("Don't know how to set uniform ${uniform.type}")
			}
		}

		when (blending) {
			BlendMode.NONE -> {
				gl.glDisable(GL2.GL_BLEND)
			}
			BlendMode.OVERLAY -> {
				gl.glEnable(GL2.GL_BLEND)
				gl.glBlendFuncSeparate(GL2.GL_SRC_ALPHA, GL2.GL_ONE_MINUS_SRC_ALPHA, GL2.GL_ONE, GL2.GL_ONE_MINUS_SRC_ALPHA)
			}
			else -> Unit
		}

		gl.glDrawElements(type.glDrawMode, vertexCount, GL2.GL_UNSIGNED_SHORT, offset.toLong())

		gl.glActiveTexture(GL2.GL_TEXTURE0)
		for (att in vertexFormat.attributes) {
			val loc = gl.glGetAttribLocation(glProgram.id, att.name).toInt()
			if (loc >= 0) {
				gl.glDisableVertexAttribArray(loc)
			}
		}
	}

	val DrawType.glDrawMode: Int get() = when (this) {
		DrawType.TRIANGLES -> GL2.GL_TRIANGLES
	}

	val VarType.glElementType: Int get() = when (this) {
		VarType.Float1, VarType.Float2, VarType.Float3, VarType.Float4 -> GL2.GL_FLOAT
		VarType.Mat4 -> GL2.GL_FLOAT
		VarType.Bool1 -> GL2.GL_UNSIGNED_BYTE
		VarType.Byte4 -> GL2.GL_UNSIGNED_BYTE
		VarType.TextureUnit -> GL2.GL_INT
	}

	private val programs = hashMapOf<Program, AwtProgram>()
	fun getProgram(program: Program): AwtProgram = programs.getOrPut(program) { AwtProgram(gl, program) }

	class AwtProgram(val gl: GL2, val program: Program) : Closeable {
		val id = gl.glCreateProgram()
		val fragmentShaderId = createShader(GL2.GL_FRAGMENT_SHADER, program.fragment.toGlSlString())
		val vertexShaderId = createShader(GL2.GL_VERTEX_SHADER, program.vertex.toGlSlString())

		init {
			gl.glAttachShader(id, fragmentShaderId)
			gl.glAttachShader(id, vertexShaderId)
			gl.glLinkProgram(id)
			val out = IntArray(1)
			gl.glGetShaderiv(id, GL2.GL_LINK_STATUS, out, 0)
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
			val shaderId = gl.glCreateShader(type)
			gl.glShaderSource(shaderId, 1, arrayOf(str), intArrayOf(str.length), 0)
			gl.glCompileShader(shaderId)

			val out = IntArray(1)
			gl.glGetShaderiv(shaderId, GL2.GL_COMPILE_STATUS, out, 0)
			if (out[0] != GL2.GL_TRUE) {
				// gl.glGetShaderInfoLog()
				throw RuntimeException("Error Compiling Shader")
			}
			return shaderId
		}

		fun use() {
			gl.glUseProgram(id)
		}

		fun unuse() {
			gl.glUseProgram(0)
		}

		override fun close() {
			gl.glDeleteShader(fragmentShaderId)
			gl.glDeleteShader(vertexShaderId)
			gl.glDeleteProgram(id)
		}
	}

	override fun clear(color: Int, depth: Float, stencil: Int, clearColor: Boolean, clearDepth: Boolean, clearStencil: Boolean) {
		//executeInGlThread {
		val r = RGBA.getRf(color)
		val g = RGBA.getGf(color)
		val b = RGBA.getBf(color)
		val a = RGBA.getAf(color)
		gl.glClearColor(r, g, b, a)
		//gl.glClear(GL.GL_COLOR_BUFFER_BIT)
		//gl.glClearColor(1f, 1f, 0f, 1f)
		gl.glClear(GL.GL_COLOR_BUFFER_BIT)
		//}
	}

	inner class AwtBuffer(kind: Buffer.Kind) : Buffer(kind) {
		private var id = -1
		val glKind = if (kind == Buffer.Kind.INDEX) GL.GL_ELEMENT_ARRAY_BUFFER else GL.GL_ARRAY_BUFFER

		override fun afterSetMem() {
		}

		override fun close() {
			val deleteId = id
			//executeInGlThread { gl ->
			gl.glDeleteBuffers(1, IntArray(deleteId), 0)
			//}
			id = -1
		}

		fun getGlId(gl: GL2): Int {
			if (id < 0) {
				val out = IntArray(1)
				gl.glGenBuffers(1, out, 0)
				id = out[0]
			}
			if (dirty) {
				_bind(gl, id)
				gl.glBufferData(glKind, mem.length.toLong(), mem.byteBufferOrNull, GL.GL_STATIC_DRAW)
			}
			return id
		}

		fun _bind(gl: GL2, id: Int) {
			gl.glBindBuffer(glKind, id)
		}

		fun bind(gl: GL2) {
			_bind(gl, getGlId(gl))
		}
	}
}