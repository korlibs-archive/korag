package com.soywiz.korag.haxelime

import com.jtransc.JTranscSystem
import com.soywiz.korag.AG
import com.soywiz.korag.AGFactory

class AGFactoryHaxeLime : AGFactory() {
	override val available: Boolean = JTranscSystem.isHaxe()
	override val priority = 500

	override fun create(): AG {
		TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
	}
}