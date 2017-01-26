@file:Suppress("unused")

package com.soywiz.korag.shader

import com.soywiz.korio.error.invalidOp
import java.io.Closeable

enum class VarType(val bytesSize: Int, val elementCount: Int) {
	TextureUnit(4, elementCount = 1),
	Bool1(1, elementCount = 1),
	Int1(4, elementCount = 1),
	Float1(4, elementCount = 1),
	Float2(8, elementCount = 2),
	Float3(12, elementCount = 3),
	Float4(16, elementCount = 4),
	Mat4(4 * 4 * 4, elementCount = 16),
	Byte4(4, elementCount = 4)
}

//val out_Position = Output("gl_Position", VarType.Float4)
//val out_FragColor = Output("gl_FragColor", VarType.Float4)

enum class ShaderType {
	VERTEX, FRAGMENT
}

open class Operand(val type: VarType) {
}

open class Variable(val name: String, type: VarType) : Operand(type) {
	var id: Int = 0
	var data: Any? = null
}

open class Attribute(name: String, type: VarType, val normalized: Boolean) : Variable(name, type)
open class Varying(name: String, type: VarType) : Variable(name, type)
open class Uniform(name: String, type: VarType) : Variable(name, type)
open class Temp(id: Int, type: VarType) : Variable("temp$id", type)
object Output : Variable("out", VarType.Float4)

class Program(val vertex: VertexShader, val fragment: FragmentShader, val name: String = "program") : Closeable {
	override fun close() {
	}

	override fun toString(): String = "Program[$name]"

	class Binop(val left: Operand, val op: String, val right: Operand) : Operand(left.type)
	class IntLiteral(val value: Int) : Operand(VarType.Int1)
	class FloatLiteral(val value: Float) : Operand(VarType.Float1)
	class BoolLiteral(val value: Boolean) : Operand(VarType.Bool1)

	class Vector(type: VarType, val ops: List<Operand>) : Operand(type) {
	}

	class Swizzle(val left: Operand, val swizzle: String) : Operand(left.type)

	class Func(val name: String, val ops: List<Operand>) : Operand(VarType.Float1)

	sealed class Stm {
		class Stms(val stms: List<Stm>) : Stm()
		class Set(val to: Operand, val from: Operand) : Stm()
		class If(val cond: Operand, val tbody: Stm, var fbody: Stm? = null) : Stm()
	}

	// http://mew.cx/glsl_quickref.pdf
	class Builder(val type: ShaderType) {
		val outputStms = arrayListOf<Stm>()

		//inner class BuildIf(val stmIf: Stm.If) {
		//	fun ELSEIF(cond: Operand, callback: Builder.() -> Unit): BuildIf {
		//		//val body = Builder(type)
		//		//body.callback()
		//		//outputStms += Stm.If(cond, Stm.Stms(body.outputStms))
		//		TODO()
		//	}
//
		//	infix fun ELSE(callback: Builder.() -> Unit) {
		//		//val body = Builder(type)
		//		//body.callback()
		//		//outputStms += Stm.If(cond, Stm.Stms(body.outputStms))
		//		TODO()
		//	}
		//}

		infix fun Stm.If.ELSE(callback: Builder.() -> Unit) {
			val body = Builder(type)
			body.callback()
			this.fbody = Stm.Stms(body.outputStms)
		}

		inline fun IF(cond: Operand, callback: Builder.() -> Unit): Stm.If {
			val body = Builder(type)
			body.callback()
			val stmIf = Stm.If(cond, Stm.Stms(body.outputStms))
			outputStms += stmIf
			return stmIf
		}

		fun SET(target: Operand, expr: Operand) {
			outputStms += Stm.Set(target, expr)
		}

		infix fun Operand.set(from: Operand) {
			outputStms += Stm.Set(this, from)
		}

		fun Operand.assign(from: Operand) {
			outputStms += Stm.Set(this, from)
		}

		//infix fun Operand.set(to: Operand) = Stm.Set(this, to)
		val out: Output = Output
		//fun out(to: Operand) = Stm.Set(if (type == ShaderType.VERTEX) out_Position else out_FragColor, to)

		fun sin(arg: Operand) = Func("sin", listOf(arg))
		fun cos(arg: Operand) = Func("cos", listOf(arg))
		fun tan(arg: Operand) = Func("tan", listOf(arg))

		fun asin(arg: Operand) = Func("asin", listOf(arg))
		fun acos(arg: Operand) = Func("acos", listOf(arg))
		fun atan(arg: Operand) = Func("atan", listOf(arg))

		fun radians(arg: Operand) = Func("radians", listOf(arg))
		fun degrees(arg: Operand) = Func("degrees", listOf(arg))

		// Sampling
		fun texture2D(a: Operand, b: Operand) = Func("texture2D", listOf(a, b))

		fun pow(b: Operand, e: Operand) = Func("pow", listOf(b, e))
		fun exp(v: Operand) = Func("exp", listOf(v))
		fun exp2(v: Operand) = Func("exp2", listOf(v))
		fun log(v: Operand) = Func("log", listOf(v))
		fun log2(v: Operand) = Func("log2", listOf(v))
		fun sqrt(v: Operand) = Func("sqrt", listOf(v))
		fun inversesqrt(v: Operand) = Func("inversesqrt", listOf(v))

		fun abs(v: Operand) = Func("abs", listOf(v))
		fun sign(v: Operand) = Func("sign", listOf(v))
		fun ceil(v: Operand) = Func("ceil", listOf(v))
		fun floor(v: Operand) = Func("floor", listOf(v))
		fun fract(v: Operand) = Func("fract", listOf(v))
		fun clamp(v: Operand, min: Operand, max: Operand) = Func("clamp", listOf(v, min, max))
		fun min(a: Operand, b: Operand) = Func("min", listOf(a, b))
		fun max(a: Operand, b: Operand) = Func("max", listOf(a, b))
		fun mod(a: Operand, b: Operand) = Func("mod", listOf(a, b))
		fun step(a: Operand, b: Operand) = Func("step", listOf(a, b))
		fun smoothstep(a: Operand, b: Operand, c: Operand) = Func("smoothstep", listOf(a, b, c))
		fun mix(a: Operand, b: Operand, step: Operand) = Func("mix", listOf(a, b, step))

		val Int.lit: IntLiteral get() = IntLiteral(this)
		val Float.lit: FloatLiteral get() = FloatLiteral(this)
		val Boolean.lit: BoolLiteral get() = BoolLiteral(this)
		fun lit(type: VarType, vararg ops: Operand): Operand = Vector(type, ops.toList())
		fun vec4(vararg ops: Operand): Operand = Vector(VarType.Float4, ops.toList())
		//fun Operand.swizzle(swizzle: String): Operand = Swizzle(this, swizzle)
		operator fun Operand.get(swizzle: String) = Swizzle(this, swizzle)

		operator fun Operand.minus(that: Operand) = Binop(this, "-", that)
		operator fun Operand.plus(that: Operand) = Binop(this, "+", that)
		operator fun Operand.times(that: Operand) = Binop(this, "*", that)
		operator fun Operand.div(that: Operand) = Binop(this, "/", that)
		operator fun Operand.rem(that: Operand) = Binop(this, "%", that)
	}

	open class Visitor {
		open fun visit(stm: Stm) = when (stm) {
			is Stm.Stms -> visit(stm)
			is Stm.Set -> visit(stm)
			is Stm.If -> visit(stm)
		}

		open fun visit(stms: Stm.Stms) {
			for (stm in stms.stms) visit(stm)
		}

		open fun visit(stm: Stm.If) {
			visit(stm.cond)
			visit(stm.tbody)
		}

		open fun visit(stm: Stm.Set) {
			visit(stm.from)
			visit(stm.to)
		}

		open fun visit(operand: Operand) = when (operand) {
			is Variable -> visit(operand)
			is Binop -> visit(operand)
			is BoolLiteral -> visit(operand)
			is IntLiteral -> visit(operand)
			is FloatLiteral -> visit(operand)
			is Vector -> visit(operand)
			is Swizzle -> visit(operand)
			is Func -> visit(operand)
			else -> invalidOp("Don't know how to visit operand $operand")
		}

		open fun visit(func: Func) {
			for (op in func.ops) visit(op)
		}

		open fun visit(operand: Variable) = when (operand) {
			is Attribute -> visit(operand)
			is Varying -> visit(operand)
			is Uniform -> visit(operand)
			is Output -> visit(operand)
			is Temp -> visit(operand)
			else -> invalidOp("Don't know how to visit basename $operand")
		}

		open fun visit(temp: Temp) {
		}

		open fun visit(attribute: Attribute) {
		}

		open fun visit(varying: Varying) {
		}

		open fun visit(uniform: Uniform) {
		}

		open fun visit(output: Output) {
		}

		open fun visit(operand: Binop) {
			visit(operand.left)
			visit(operand.right)
		}

		open fun visit(operand: Swizzle) {
			visit(operand.left)
		}

		open fun visit(operand: Vector) {
			for (op in operand.ops) visit(op)
		}

		open fun visit(operand: IntLiteral) {
		}

		open fun visit(operand: FloatLiteral) {
		}

		open fun visit(operand: BoolLiteral) {
		}
	}
}

open class Shader(val stm: Program.Stm)

open class VertexShader(stm: Program.Stm) : Shader(stm)
open class FragmentShader(stm: Program.Stm) : Shader(stm)

fun VertexShader(callback: Program.Builder.() -> Unit): VertexShader {
	val builder = Program.Builder(ShaderType.VERTEX)
	builder.callback()
	return VertexShader(Program.Stm.Stms(builder.outputStms))
}

fun FragmentShader(callback: Program.Builder.() -> Unit): FragmentShader {
	val builder = Program.Builder(ShaderType.FRAGMENT)
	builder.callback()
	return FragmentShader(Program.Stm.Stms(builder.outputStms))
}

class VertexLayout(val attributes: List<Attribute>) {
	constructor(vararg attributes: Attribute) : this(attributes.toList())

	var totalSize = 0; private set
	val attributePositions = attributes.map {
		val out = totalSize
		totalSize += it.type.bytesSize
		out
	}

	override fun toString(): String = "VertexLayout[${attributes.map { it.name }.joinToString(", ")}]"
}

@Deprecated("Use VertexLayout", ReplaceWith("VertexLayout"))
typealias VertexFormat = VertexLayout
