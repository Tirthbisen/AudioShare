package com.example.audioshare

data class ServerConfig(
    val ip        : String,
    val port      : Int,
    val sampleRate: Int,
    val bitDepth  : Int,
    val channels  : Int
)