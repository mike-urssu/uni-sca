package sca

import java.nio.file.Path

fun fixture(name: String): Path = Path.of(object {}.javaClass.getResource("/fixtures/$name")!!.toURI())
