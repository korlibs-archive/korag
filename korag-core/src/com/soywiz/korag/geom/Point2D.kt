package com.soywiz.korag.geom

class Point2D(var x: Double = 0.0, var y: Double = x) {
	fun setTo(x: Double, y: Double): Point2D {
		this.x = x
		this.y = y
		return this
	}

	fun copyFrom(that: Point2D) = setTo(that.x, that.y)

	fun setToTransform(mat: Matrix2D, p: Point2D): Point2D = setToTransform(mat, p.x, p.y)

	fun setToTransform(mat: Matrix2D, x: Double, y: Double): Point2D = setTo(
		mat.transformX(x, y),
		mat.transformY(x, y)
	)

	fun setToAdd(a: Point2D, b: Point2D): Point2D = setTo(
		a.x + b.x,
		a.y + b.y
	)

	operator fun plusAssign(that: Point2D) {
		setTo(this.x + that.x, this.y + that.y)
	}
}