package com.soywiz.korag.shader

import com.soywiz.korio.error.invalidOp

class GlslGenerator(val kind: ShaderType, val gles: Boolean = false) : Program.Visitor() {
	private val temps = hashSetOf<Temp>()
	private val attributes = hashSetOf<Attribute>()
	private val varyings = hashSetOf<Varying>()
	private val uniforms = hashSetOf<Uniform>()
	private var programStr = StringBuilder()

	fun typeToString(type: VarType) = when (type) {
		VarType.Float1 -> "float"
		VarType.Float2 -> "vec2"
		VarType.Float3 -> "vec3"
		VarType.Float4 -> "vec4"
		VarType.Byte4 -> "vec4"
		VarType.Mat4 -> "mat4"
		VarType.TextureUnit -> "sampler2D"
		else -> invalidOp("Don't know how to serialize type $type")
	}

	fun generate(root: Program.Stm): String {
		temps.clear()
		attributes.clear()
		varyings.clear()
		uniforms.clear()
		programStr.setLength(0)
		visit(root)

		val prefix = arrayListOf<String>()

		if (kind == ShaderType.FRAGMENT && attributes.isNotEmpty()) {
			throw RuntimeException("Can't use attributes in fragment shader")
		}
		for (a in attributes) prefix += "attribute ${typeToString(a.type)} ${a.name};"
		for (u in uniforms) prefix += "uniform ${typeToString(u.type)} ${u.name};"
		for (v in varyings) prefix += "varying ${typeToString(v.type)} ${v.name};"

		val precissions = arrayListOf<String>()
		precissions += "#ifdef GL_ES"
		precissions += "precision mediump float;"
		precissions += "precision mediump int;"
		precissions += "precision lowp sampler2D;"
		precissions += "precision lowp samplerCube;"
		precissions += "#endif"

		val tempsStr = temps.map {
			typeToString(it.type) + " " + it.name + ";"
		}

		return precissions.joinToString("\n") + "\n" + prefix.joinToString("\n") + "\n" + "void main() {\n" + tempsStr.joinToString("\n") + programStr.toString() + "}\n"
	}

	override fun visit(stms: Program.Stm.Stms) {
		programStr.append("{")
		for (stm in stms.stms) visit(stm)
		programStr.append("}")
	}

	override fun visit(stm: Program.Stm.Set) {
		visit(stm.to)
		programStr.append(" = ")
		visit(stm.from)
		programStr.append(";\n")
	}

	override fun visit(operand: Program.Vector) {
		programStr.append("vec4(")
		var first = true
		for (op in operand.ops) {
			if (!first) {
				programStr.append(",")
			}
			visit(op)
			first = false
		}
		programStr.append(")")
	}

	override fun visit(operand: Program.Binop) {
		programStr.append("(")
		visit(operand.left)
		programStr.append(operand.op)
		visit(operand.right)
		programStr.append(")")
	}

	override fun visit(func: Program.Func) {
		programStr.append(func.name)
		programStr.append("(")
		var first = true
		for (op in func.ops) {
			if (!first) programStr.append(", ")
			visit(op)
			first = false
		}
		programStr.append(")")
	}

	override fun visit(_IF: Program.Stm.If) {
		programStr.append("if (")
		visit(_IF.cond)
		programStr.append(")")
		visit(_IF.body)
	}

	override fun visit(operand: Variable) {
		if (operand is Output) {
			programStr.append(when (kind) {
				ShaderType.VERTEX -> "gl_Position"
				ShaderType.FRAGMENT -> "gl_FragColor"
			})
		} else {
			programStr.append(operand.name)
		}
		super.visit(operand)
	}

	override fun visit(temp: Temp) {
		temps += temp
		super.visit(temp)
	}

	override fun visit(attribute: Attribute) {
		attributes += attribute
		super.visit(attribute)
	}

	override fun visit(varying: Varying) {
		varyings += varying
		super.visit(varying)
	}

	override fun visit(uniform: Uniform) {
		uniforms += uniform
		super.visit(uniform)
	}

	override fun visit(output: Output) {
		super.visit(output)
	}

	override fun visit(operand: Program.FloatLiteral) {
		programStr.append(operand.value)
		super.visit(operand)
	}

	override fun visit(operand: Program.BoolLiteral) {
		programStr.append(operand.value)
		super.visit(operand)
	}

	override fun visit(operand: Program.Swizzle) {
		visit(operand.left)
		programStr.append(".${operand.swizzle}")
	}
}