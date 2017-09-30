package com.soywiz.korag.geom

import com.soywiz.korio.lang.JvmField
import kotlin.math.cos
import kotlin.math.sin

class Matrix2D(
	@JvmField var a: Double = 1.0,
	@JvmField var b: Double = 0.0,
	@JvmField var c: Double = 0.0,
	@JvmField var d: Double = 1.0,
	@JvmField var tx: Double = 0.0,
	@JvmField var ty: Double = 0.0
) {
	fun setTo(a: Double, b: Double, c: Double, d: Double, tx: Double, ty: Double): Matrix2D {
		this.a = a
		this.b = b
		this.c = c
		this.d = d
		this.tx = tx
		this.ty = ty
		return this
	}

	fun copyFrom(that: Matrix2D) {
		setTo(that.a, that.b, that.c, that.d, that.tx, that.ty)
	}

	fun rotate(theta: Double) = this.apply {
		val cos = cos(theta)
		val sin = sin(theta)

		val a1 = a * cos - b * sin
		b = a * sin + b * cos
		a = a1

		val c1 = c * cos - d * sin
		d = c * sin + d * cos
		c = c1

		val tx1 = tx * cos - ty * sin
		ty = tx * sin + ty * cos
		tx = tx1
	}

	fun scale(sx: Double, sy: Double) = setTo(a * sx, b * sx, c * sy, d * sy, tx * sx, ty * sy)
	fun prescale(sx: Double, sy: Double) = setTo(a * sx, b * sx, c * sy, d * sy, tx, ty)

	fun pretranslate(dx: Double, dy: Double) = this.apply {
		tx += a * dx + c * dy
		ty += b * dx + d * dy
	}

	fun prerotate(theta: Double) = this.apply {
		val m = com.soywiz.korag.geom.Matrix2D()
		m.rotate(theta)
		this.premulitply(m)
	}

	fun premulitply(m: Matrix2D) = this.premulitply(m.a, m.b, m.c, m.d, m.tx, m.ty)

	fun premulitply(la: Double, lb: Double, lc: Double, ld: Double, ltx: Double, lty: Double): Matrix2D = setTo(
		la * a + lb * c,
		la * b + lb * d,
		lc * a + ld * c,
		lc * b + ld * d,
		ltx * a + lty * c + tx,
		ltx * b + lty * d + ty
	)

	fun multiply(l: Matrix2D, r: Matrix2D): Matrix2D = setTo(
		l.a * r.a + l.b * r.c,
		l.a * r.b + l.b * r.d,
		l.c * r.a + l.d * r.c,
		l.c * r.b + l.d * r.d,
		l.tx * r.a + l.ty * r.c + r.tx,
		l.tx * r.b + l.ty * r.d + r.ty
	)

	fun transform(px: Double, py: Double, out: Point2D = Point2D()): Point2D = out.setTo(transformX(px, py), transformY(px, py))

	fun transformX(px: Double, py: Double): Double = this.a * px + this.c * py + this.tx
	fun transformY(px: Double, py: Double): Double = this.d * py + this.b * px + this.ty

	fun transformXf(px: Double, py: Double): Float = (this.a * px + this.c * py + this.tx).toFloat()
	fun transformYf(px: Double, py: Double): Float = (this.d * py + this.b * px + this.ty).toFloat()

	override fun toString(): String {
		return "Matrix2D(a=$a, b=$b, c=$c, d=$d, tx=$tx, ty=$ty)"
	}

	fun setToIdentity() = setTo(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)

	fun setToInverse(): Matrix2D {
		var norm = a * d - b * c

		if (norm == 0.0) {
			a = 0.0
			b = 0.0
			c = 0.0
			d = 0.0
			tx = -tx
			ty = -ty
		} else {
			norm = 1.0 / norm
			val a1 = d * norm
			d = a * norm
			a = a1
			b *= -norm
			c *= -norm

			val tx1 = -a * tx - c * ty
			ty = -b * tx - d * ty
			tx = tx1
		}

		return this
	}
}