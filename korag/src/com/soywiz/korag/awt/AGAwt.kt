package com.soywiz.korag.awt

import com.soywiz.korag.AG
import com.soywiz.korag.AGFactory

class AGFactoryAwt : AGFactory() {
	override val priority: Int = 500
	override fun create(): AG = AGAwt()
}

class AGAwt : AG() {
}