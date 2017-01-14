package com.soywiz.korag.android

import com.soywiz.korag.AG
import com.soywiz.korag.AGFactory

class AGFactoryAndroid : AGFactory() {
	override val available: Boolean = true // @TODO: Detect android
	override val priority: Int = 1500
	override fun create(): AG = AGAndroid()
}

class AGAndroid : AG() {
}