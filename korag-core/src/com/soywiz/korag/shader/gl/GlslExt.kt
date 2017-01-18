package com.soywiz.korag.shader.gl

import com.soywiz.korag.shader.FragmentShader
import com.soywiz.korag.shader.ShaderType
import com.soywiz.korag.shader.VertexShader

fun VertexShader.toGlSlString(gles: Boolean = false) = GlslGenerator(ShaderType.VERTEX, gles).generate(this.stm)
fun FragmentShader.toGlSlString(gles: Boolean = false) = GlslGenerator(ShaderType.FRAGMENT, gles).generate(this.stm)