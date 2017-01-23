package com.soywiz.korag.shaders

import com.soywiz.korag.DefaultShaders
import com.soywiz.korag.shader.VertexShader
import com.soywiz.korag.shader.gl.toGlSlString
import org.junit.Assert
import org.junit.Test

class ShadersTest {
	@Test
	fun name() {
		val vs = VertexShader {
			IF(true.lit) {
				SET(DefaultShaders.t_Temp1, 1.lit * 2.lit)
			} ELSE {
				SET(DefaultShaders.t_Temp1, 3.lit * 4.lit)
			}
		}

		// @TODO: Optimizer phase!
		Assert.assertEquals(
			"void main() {vec4 temp0;{if (true) {temp0 = (1*2);} else {temp0 = (3*4);}}}",
			vs.toGlSlString(gles = false).trim()
		)
	}
}