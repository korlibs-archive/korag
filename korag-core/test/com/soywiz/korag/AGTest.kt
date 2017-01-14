package com.soywiz.korag

import org.junit.Test

class AGTest {
	@Test
	fun name() {
		val ag = agFactory.create()
		val buffer = ag.createIndexBuffer()
		buffer.upload(intArrayOf(1, 2, 3, 4))
	}
}