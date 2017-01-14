package com.soywiz.korag.software

import com.soywiz.korag.AG
import com.soywiz.korag.AGFactory

class AGFactorySoftware() : AGFactory() {
	override val priority: Int = 2000
	override fun create(): AG = AGSoftware()
}

class AGSoftware : AG() {
}