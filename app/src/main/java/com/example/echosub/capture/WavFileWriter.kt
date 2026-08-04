package com.example.echosub.capture

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PCM 16bit little-endian 스트림을 WAV 파일로 기록한다.
 *
 * 44바이트 표준 헤더를 먼저 써두고, PCM 청크를 순차적으로 append 한다.
 * 총 데이터 길이는 스트리밍 중에는 알 수 없으므로, [close]에서
 * RandomAccessFile로 헤더의 크기 필드(ChunkSize, Subchunk2Size)를 되돌아가 패치한다.
 *
 * close()가 호출되지 않으면(강제 종료 등) 헤더의 크기 필드가 0으로 남아
 * 재생 불가능한 파일이 될 수 있다 — 반드시 정상 종료 경로를 타야 한다.
 */
class WavFileWriter(
    private val file: File,
    private val sampleRate: Int,
    private val channelCount: Int,
    private val bitsPerSample: Int = 16,
) : AutoCloseable {

    private val raf = RandomAccessFile(file, "rw")
    private var dataBytesWritten: Long = 0
    private var closed = false

    init {
        raf.setLength(0)
        writePlaceholderHeader()
    }

    @Synchronized
    fun write(buffer: ByteArray, offset: Int, length: Int) {
        if (closed || length <= 0) return
        raf.write(buffer, offset, length)
        dataBytesWritten += length
    }

    @Synchronized
    override fun close() {
        if (closed) return
        patchHeaderSizes()
        raf.close()
        closed = true
    }

    val bytesWritten: Long get() = dataBytesWritten

    private fun writePlaceholderHeader() {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        val byteRate = sampleRate * channelCount * (bitsPerSample / 8)
        val blockAlign = channelCount * (bitsPerSample / 8)

        header.put("RIFF".toByteArray())
        header.putInt(0) // ChunkSize placeholder — patched on close()
        header.put("WAVE".toByteArray())

        header.put("fmt ".toByteArray())
        header.putInt(16) // Subchunk1Size for PCM
        header.putShort(1) // AudioFormat = PCM
        header.putShort(channelCount.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(bitsPerSample.toShort())

        header.put("data".toByteArray())
        header.putInt(0) // Subchunk2Size placeholder — patched on close()

        raf.write(header.array())
    }

    private fun patchHeaderSizes() {
        val dataSize = dataBytesWritten.toInt()
        val chunkSize = 36 + dataSize

        raf.seek(4)
        raf.write(intToLeBytes(chunkSize))

        raf.seek(40)
        raf.write(intToLeBytes(dataSize))
    }

    private fun intToLeBytes(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
}
