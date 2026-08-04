package com.example.echosub.util

/** 저장된/전달된 이름으로 enum 값을 찾고, 없거나 못 찾으면 기본값을 쓴다. */
inline fun <reified T : Enum<T>> enumByName(name: String?, default: T): T =
    enumValues<T>().firstOrNull { it.name == name } ?: default
