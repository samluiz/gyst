package com.samluiz

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform