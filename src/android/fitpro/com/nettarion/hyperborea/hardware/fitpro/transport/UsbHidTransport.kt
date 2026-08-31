package com.nettarion.hyperborea.hardware.fitpro.transport

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import com.nettarion.hyperborea.core.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

class UsbHidTransport(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val inEndpoint: UsbEndpoint,
    private val outEndpoint: UsbEndpoint,
    private val logger: AppLogger,
) : HidTransport {

    @Volatile
    private var _isOpen = false
    override val isOpen: Boolean get() = _isOpen

    @Volatile
    private var consecutiveReadFailures = 0

    override suspend fun open() {
        if (_isOpen) return
        connection.claimInterface(usbInterface, true)
        consecutiveReadFailures = 0
        _isOpen = true
        logger.d(TAG, "Transport opened")
    }

    override suspend fun close() {
        if (!_isOpen) return
        _isOpen = false
        try {
            connection.releaseInterface(usbInterface)
            connection.close()
            logger.d(TAG, "Transport closed")
        } catch (e: Exception) {
            logger.w(TAG, "USB close error: ${e.message}")
        }
    }

    override suspend fun write(data: ByteArray) {
        if (!_isOpen) throw IllegalStateException("Transport not open")
        require(data.size <= MAX_PACKET_SIZE) { "Packet too large: ${data.size} > $MAX_PACKET_SIZE" }
        // Pad to 64 bytes — the MCU expects full-size USB packets
        val padded = if (data.size < MAX_PACKET_SIZE) data.copyOf(MAX_PACKET_SIZE) else data
        val transferred = connection.bulkTransfer(outEndpoint, padded, padded.size, WRITE_TIMEOUT_MS)
        if (transferred < 0) throw IllegalStateException("USB write failed (transferred=$transferred)")
    }

    override suspend fun readPacket(): ByteArray? {
        if (!_isOpen) return null
        val buffer = ByteArray(MAX_PACKET_SIZE)
        val transferred = connection.bulkTransfer(inEndpoint, buffer, buffer.size, READ_TIMEOUT_MS)
        if (transferred > 0) {
            consecutiveReadFailures = 0
            return buffer.copyOf(transferred)
        }
        // bulkTransfer reports a timed-out read and a dead link identically, as a negative return,
        // so a bare null cannot distinguish "nothing this tick" from "the interface was stolen".
        // Returning null forever is what let a stolen claim freeze the session in silence: the
        // poll loop treats null as benign and never counts an error, so the session state stays
        // Streaming and nothing ever reconnects. A console that answers every poll does not go
        // quiet for this long, so a run of failures is escalated as an exception the session can
        // count and act on.
        if (++consecutiveReadFailures >= MAX_CONSECUTIVE_READ_FAILURES) {
            throw IllegalStateException(
                "USB read failed $consecutiveReadFailures times in a row (transferred=$transferred)",
            )
        }
        return null
    }

    override suspend fun clearBuffer() {
        if (!_isOpen) return
        val clearCmd = ByteArray(MAX_PACKET_SIZE).also { it[0] = 0xFF.toByte() }
        val readBuf = ByteArray(MAX_PACKET_SIZE)
        var consecutiveFf = 0
        var attempts = 0

        withContext(Dispatchers.IO) {
            while (consecutiveFf < 2 && attempts < MAX_CLEAR_ATTEMPTS) {
                connection.bulkTransfer(outEndpoint, clearCmd, clearCmd.size, 500)
                delay(50)
                val n = connection.bulkTransfer(inEndpoint, readBuf, readBuf.size, 500)
                if (n > 0 && readBuf[0] == 0xFF.toByte()) {
                    consecutiveFf++
                } else {
                    consecutiveFf = 0
                }
                attempts++
            }
        }

        logger.i(TAG, "Buffer cleared after $attempts attempts (ff=$consecutiveFf)")
    }

    override fun incoming(): Flow<ByteArray> = flow {
        val buffer = ByteArray(MAX_PACKET_SIZE)
        while (currentCoroutineContext().isActive && _isOpen) {
            val transferred = connection.bulkTransfer(inEndpoint, buffer, buffer.size, READ_TIMEOUT_MS)
            if (transferred > 0) {
                emit(buffer.copyOf(transferred))
            } else if (transferred < 0 && !_isOpen) {
                break
            }
        }
    }.flowOn(Dispatchers.IO)

    private companion object {
        const val TAG = "UsbHidTransport"
        const val MAX_PACKET_SIZE = 64
        const val READ_TIMEOUT_MS = 1000
        const val WRITE_TIMEOUT_MS = 1000
        const val MAX_CLEAR_ATTEMPTS = 10
        const val MAX_CONSECUTIVE_READ_FAILURES = 8
    }
}
