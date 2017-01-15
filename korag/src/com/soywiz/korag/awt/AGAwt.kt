package com.soywiz.korag.awt

import com.jogamp.opengl.*
import com.jogamp.opengl.awt.GLCanvas
import com.soywiz.korag.AG
import com.soywiz.korag.AGFactory
import com.soywiz.korim.color.RGBA


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

	init {
		glcanvas.addGLEventListener(object : GLEventListener {
			override fun reshape(d: GLAutoDrawable, p1: Int, p2: Int, p3: Int, p4: Int) {
				ad = d
				//println("a")
			}

			override fun display(d: GLAutoDrawable) {
				ad = d
				//println("b")
				gl = d.gl
				onRender(this@AGAwt)
				gl.glFlush()
				//gl.glClearColor(1f, 1f, 0f, 1f)
				//gl.glClear(GL.GL_COLOR_BUFFER_BIT)
				//d.swapBuffers()
			}

			override fun init(d: GLAutoDrawable) {
				ad = d
				//println("c")
			}

			override fun dispose(d: GLAutoDrawable) {
				ad = d
				//println("d")
			}
		})
	}

	fun executeInGlThread(callback: () -> Unit) {
		// @TODO:
		callback()
	}

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
}