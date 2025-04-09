package com.example.tabletlink_android

class Utils {
    companion object {
        init {
            System.loadLibrary("xor_native")
        }

        external fun xorFrames(dst: ByteArray?, frame: ByteArray?, delta: ByteArray?)
    }
}
