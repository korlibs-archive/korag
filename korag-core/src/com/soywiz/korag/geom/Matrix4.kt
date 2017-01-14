package com.soywiz.korag.geom

class Matrix4(val data: FloatArray = floatArrayOf(
	1f, 0f, 0f, 0f,
	0f, 1f, 0f, 0f,
	0f, 0f, 1f, 0f,
	0f, 0f, 0f, 1f
)) {
	operator fun get(x: Int, y: Int) = data[y * 4 + x]

	companion object {
		private val TEMP = Matrix4()
		private val TEMP_LINE = FloatArray(4)
	}

	fun transpose(): Matrix4 {
		TEMP.copyFrom(this)
		this.setRow(0, com.soywiz.korag.geom.Matrix4.TEMP.getColumn(0, com.soywiz.korag.geom.Matrix4.TEMP_LINE))
		this.setRow(1, com.soywiz.korag.geom.Matrix4.TEMP.getColumn(1, com.soywiz.korag.geom.Matrix4.TEMP_LINE))
		this.setRow(2, com.soywiz.korag.geom.Matrix4.TEMP.getColumn(2, com.soywiz.korag.geom.Matrix4.TEMP_LINE))
		this.setRow(3, com.soywiz.korag.geom.Matrix4.TEMP.getColumn(3, com.soywiz.korag.geom.Matrix4.TEMP_LINE))
		return this
	}

	fun getRow(n: Int, target: FloatArray = FloatArray(4)): FloatArray {
		val m = n * 4
		target[0] = data[m + 0]
		target[1] = data[m + 1]
		target[2] = data[m + 2]
		target[3] = data[m + 3]
		return target
	}

	fun getColumn(n: Int, target: FloatArray = FloatArray(4)): FloatArray {
		target[0] = data[n + 0]
		target[1] = data[n + 4]
		target[2] = data[n + 8]
		target[3] = data[n + 12]
		return target
	}

	fun setRow(n: Int, a: Float, b: Float, c: Float, d: Float): Matrix4 {
		val m = n * 4
		data[m + 0] = a
		data[m + 1] = b
		data[m + 2] = c
		data[m + 3] = d
		return this
	}

	fun setRow(n: Int, data: FloatArray): Matrix4 = setRow(n, data[0], data[1], data[2], data[3])
	fun setColumn(n: Int, data: FloatArray): Matrix4 = setColumn(n, data[0], data[1], data[2], data[3])

	fun setColumn(n: Int, a: Float, b: Float, c: Float, d: Float): Matrix4 {
		data[n + 0] = a
		data[n + 4] = b
		data[n + 8] = c
		data[n + 12] = d
		return this
	}

	fun setTo(
		a0: Float, b0: Float, c0: Float, d0: Float,
		a1: Float, b1: Float, c1: Float, d1: Float,
		a2: Float, b2: Float, c2: Float, d2: Float,
		a3: Float, b3: Float, c3: Float, d3: Float
	): Matrix4 {
		setRow(0, a0, b0, c0, d0)
		setRow(1, a1, b1, c1, d1)
		setRow(2, a2, b2, c2, d2)
		setRow(3, a3, b3, c3, d3)
		return this
	}

	fun copyFrom(that: Matrix4): Matrix4 {
		for (n in 0 until 16) this.data[n] = that.data[n]
		return this
	}

	fun copyFrom(that: Matrix2D): Matrix4 {
		return setTo(
			that.a.toFloat(), that.b.toFloat(), 0f, 0f,
			that.c.toFloat(), that.d.toFloat(), 0f, 0f,
			0f, 0f, 1f, 0f,
			that.tx.toFloat(), that.ty.toFloat(), 0f, 1f
		)
	}

	fun setToOrtho(left: Float, top: Float, right: Float, bottom: Float, near: Float, far: Float): Matrix4 {
		val lr = 1 / (left - right)
		val bt = 1 / (bottom - top)
		val nf = 1 / (near - far)

		setRow(0, -2 * lr, 0f, 0f, 0f)
		setRow(1, 0f, -2 * bt, 0f, 0f)
		setRow(2, 0f, 0f, 2 * nf, 0f)
		setRow(3, (left + right) * lr, (top + bottom) * bt, (far + near) * nf, 1f)

		return this
	}
}