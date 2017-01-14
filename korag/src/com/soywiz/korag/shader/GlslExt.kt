package com.soywiz.korag.shader

fun VertexShader.toGlSlString(gles: Boolean = false) = GlslGenerator(ShaderType.VERTEX, gles).generate(this.stm)
fun FragmentShader.toGlSlString(gles: Boolean = false) = GlslGenerator(ShaderType.FRAGMENT, gles).generate(this.stm)