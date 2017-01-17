package com.soywiz.korag.awt

import com.jogamp.opengl.*
import com.jogamp.opengl.awt.GLCanvas
import com.soywiz.korag.AG
import com.soywiz.korag.AGFactory
import com.soywiz.korim.color.RGBA
import java.util.*
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.ConcurrentLinkedQueue


class AGFactoryAwt : AGFactory() {
	override val priority: Int = 500
	override fun create(): AG = AGAwt()
}

class AGAwt : AG() {
	var glprofile = GLProfile.getDefault()
	var glcapabilities = GLCapabilities(glprofile)
	val glcanvas = GLCanvas(glcapabilities)
	override val nativeComponent = glcanvas
	lateinit var ad: GLAutoDrawable
	lateinit var gl: GL
	lateinit var glThread: Thread

	val queue = LinkedList<(gl: GL) -> Unit>()

	init {
		glcanvas.addGLEventListener(object : GLEventListener {
			override fun reshape(d: GLAutoDrawable, p1: Int, p2: Int, p3: Int, p4: Int) {
				glThread = Thread.currentThread()
				ad = d
				//println("a")
			}

			override fun display(d: GLAutoDrawable) {
				glThread = Thread.currentThread()
				ad = d
				//println("b")
				gl = d.gl

				while (true) {
					val callback = synchronized(queue) { if (queue.isNotEmpty()) queue.remove() else null } ?: break
					callback(gl)
				}

				onRender(this@AGAwt)
				gl.glFlush()

				//gl.glClearColor(1f, 1f, 0f, 1f)
				//gl.glClear(GL.GL_COLOR_BUFFER_BIT)
				//d.swapBuffers()
			}

			override fun init(d: GLAutoDrawable) {
				glThread = Thread.currentThread()
				ad = d
				//println("c")
			}

			override fun dispose(d: GLAutoDrawable) {
				glThread = Thread.currentThread()
				ad = d
				//println("d")
			}
		})
	}

	fun executeInGlThread(callback: (gl: GL) -> Unit) {
		if (glThread == Thread.currentThread()) {
			// @TODO:
			callback(this.gl)
		}else {
			synchronized(queue) { queue += callback }
		}
	}

	override fun createBuffer(kind: Buffer.Kind): Buffer = AwtBuffer(kind)

	override fun clear(color: Int, depth: Float, stencil: Int, clearColor: Boolean, clearDepth: Boolean, clearStencil: Boolean) {
		executeInGlThread {
			val r = RGBA.getRf(color)
			val g = RGBA.getGf(color)
			val b = RGBA.getBf(color)
			val a = RGBA.getAf(color)
			gl.glClearColor(r, g, b, a)
			//gl.glClear(GL.GL_COLOR_BUFFER_BIT)
			//gl.glClearColor(1f, 1f, 0f, 1f)
			gl.glClear(GL.GL_COLOR_BUFFER_BIT)
		}
	}

	inner class AwtBuffer(kind: Buffer.Kind) : Buffer(kind) {
		private var id = -1
		val glKind = if (kind == Buffer.Kind.INDEX) GL.GL_ELEMENT_ARRAY_BUFFER else GL.GL_ARRAY_BUFFER

		override fun afterSetMem() {
		}

		override fun close() {
			val deleteId = id
			executeInGlThread { gl ->
				gl.glDeleteBuffers(1, IntArray(deleteId), 0)
			}
			id = -1
		}

		fun getGlId(gl: GL): Int {
			if (id < 0) {
				val out = IntArray(1)
				gl.glGenBuffers(1, out, 0)
				id = out[0]
			}
			if (dirty) {
				bind(gl)
				gl.glBufferData(glKind, mem.length.toLong(), mem.byteBufferOrNull, GL.GL_STATIC_DRAW)
			}
			return id
		}

		fun bind(gl: GL) {
			gl.glBindBuffer(glKind, id)
		}
	}
}