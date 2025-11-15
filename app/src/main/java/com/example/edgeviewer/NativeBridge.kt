package com.example.edgeviewer

object NativeBridge {
    init {
        System.loadLibrary("native-lib")
    }

    external fun processFrame(
        frameData: ByteArray,
        width: Int,
        height: Int,
        outputBuffer: java.nio.ByteBuffer
    )
}
