/*
 * Copyright 2023 Punch Through Design LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.punchthrough.blestarterappandroid

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.punchthrough.blestarterappandroid.ble.ConnectionManager
import com.punchthrough.blestarterappandroid.ble.ConnectionManager.isConnected
import com.punchthrough.blestarterappandroid.ble.isWritable
import com.punchthrough.blestarterappandroid.ble.isWritableWithoutResponse
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.CRC32

object McuProtocol
{
    // Size
    private const val MCU_BUFFER_SIZE: Int = 1024
    private const val MCU_PACKET_SIZE: Int = 64
    private const val BLE_TRANSFER_SIZE: Int = 512
    private const val CRC_SIZE: Int = 4
    private const val STATUS_SIZE: Int = 1
    private var WRITE_CMD_SIZE: Int = 7
    
    // ------------------------------------------- MCU Protocol Defined -------------------------------------------- //
    // MCU Control Byte
    private const val MCU_READ = 0x04
    private const val MCU_WRITE = 0x00
    private const val MCU_CHECK = 0x10
    private const val API_CRC_ERROR = 0x20
    
    // MCU State
    const val MCU_STATE_OK = 0x00
    private const val MCU_STATE_BUSY = 0x01
    private const val MCU_STATE_CMD_ERROR = 0x10
    private const val MCU_STATE_CRC_ERROR = 0x20
    
    // ------------------------------------------------------------------------------------------------------------- //
    private const val BLE_WRITE_ERROR = 0x100
    private const val BLE_READ_ERROR = 0x200
    private const val BLE_TIME_OUT = 0x400
    private const val MCU_PACKET_SIZE_UNMATCHED = 0x800
    
    // ---------------------------------------------- Parameter ---------------------------------------------------- //
    private var receivedBuffer: ArrayList<Byte> = ArrayList<Byte>()
    private var receivedNotification: Boolean = false
    private var requestedDataLength: Int = 0
    private var errorCode = 0
    
    var packetIndex = 0
    var remainingBytes = 0
    var shouldRetry = false
    var retryTimes_MCU_BUSY = 0
    var retryTimes_CRC_ERROR = 0
    private var maxRetryTimes_MCU_BUSY: Int = 3
    private var maxRetryTimes_CRC_ERROR: Int = 3
    private var waitingNotificationTime: Long = 10000
    
    private var bleOperationInterval: Long = 1       // Interval time for BLE operation
    private var waitRWTime: Long = 10        // Wait time for device read/write data to target device
    private var checkIntervalTime: Long = 50 // The interval time of check device
    
    private lateinit var activity: AppCompatActivity
    private lateinit var device: BluetoothDevice
    private lateinit var characteristic: BluetoothGattCharacteristic
    // ------------------------------------------------------------------------------------------------------------ //
    
    suspend fun write(bluetoothDevice: BluetoothDevice, bluetoothGattCharacteristic: BluetoothGattCharacteristic, payload: ByteArray): Boolean
    {
        device = bluetoothDevice
        characteristic = bluetoothGattCharacteristic
        retryTimes_MCU_BUSY = 0
        retryTimes_CRC_ERROR = 0
        setNotificationTrigger(false)
        val writeType = when
        {
            characteristic.isWritable() -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            characteristic.isWritableWithoutResponse() ->
            {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            }
            
            else ->
            {
                Timber.e("Characteristic ${characteristic.uuid} cannot be written to")
                return false
            }
        }
        
        if(device.isConnected())
        {
            val writePacket = buildWritePackets(buildTestPacket(payload)) //TODO: Remove Test Data
            val packetsPerTransfer = 3
            // Check status
            do
            {
                if(!sendCheckPacket()) return false
                if(!verifyStatusPacket()) return false
            }
            while(shouldRetry)
            
            // Write packet
//            log("MCU Write is executing. Input bytes: ${payload.size}") //TODO: Mark
            remainingBytes = 0
            packetIndex = 0
            for(data in writePacket) remainingBytes += data.size
            while(packetIndex < writePacket.size)
            {
                do
                {
                    if(!sendWritePacket(writePacket, packetsPerTransfer)) return false
                    if(!verifyStatusPacket()) return false
                    else if(shouldRetry)
                    {
                        packetIndex -= if(packetIndex % packetsPerTransfer == 0) 3 else packetIndex % packetsPerTransfer
                    }
                }
                while(shouldRetry)
            }
            
            log("MCU Write is done. Total packets: ${writePacket.size}")
            return true
        }
        else
        {
            Timber.e("Not connected to ${device.address}, cannot perform characteristic write")
        }
        return false
    }
    
    suspend fun read(bluetoothDevice: BluetoothDevice, bluetoothGattCharacteristic: BluetoothGattCharacteristic, payload: ByteArray): Boolean
    {
        device = bluetoothDevice
        characteristic = bluetoothGattCharacteristic
        retryTimes_MCU_BUSY = 0
        retryTimes_CRC_ERROR = 0
        setNotificationTrigger(false)
        val writeType = when
        {
            characteristic.isWritable() -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            characteristic.isWritableWithoutResponse() ->
            {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            }
            
            else ->
            {
                Timber.e("Characteristic ${characteristic.uuid} cannot be written to")
                return false
            }
        }
        if(device.isConnected())
        {
            val data = ArrayList<Byte>()
            val readCMD = organizeReadCMDPacket()
            
            // Check status
            do
            {
                if(!sendCheckPacket()) return false
                if(!verifyStatusPacket()) return false
            }
            while(shouldRetry)
            
            // Send ReadInfo packet
            do
            {
                if(!sendReadInfoPacket(payload)) return false
                if(!verifyStatusPacket()) return false
            }
            while(shouldRetry)
            
            log("MCU Read is executing. Request length: $requestedDataLength")
            while(data.size < requestedDataLength)
            {
                // Check status
                do
                {
                    if(!sendCheckPacket()) return false
                    if(!verifyStatusPacket()) return false
                }
                while(shouldRetry)
                
                // Send ReadCMD packet
                do
                {
                    if(!sendPacket(readCMD, "ReadCMDPacket")) return false
                    if(!getReceivedData(data)) return false
                }
                while(shouldRetry)
            }
            
            log("MCU Read is done. Received data: ${data.toHexString()}")
            return true
        }
        else
        {
            Timber.e("Not connected to ${device.address}, cannot perform characteristic write")
            return false
        }
        
        return true
    }
    
    
    private suspend fun sendCheckPacket(): Boolean
    {
        val checkPacket = ArrayList<Byte>(List(MCU_PACKET_SIZE - CRC_SIZE - 1) { 0xFF.toByte() })   // Control Byte: 1 byte
        val crcBytes = generateCRC32(checkPacket)
        // Put CRC32 Bytes after Control Byte
        checkPacket.addAll(0, crcBytes)
        checkPacket.add(0, MCU_CHECK.toByte())
        
        if(!sendPacket(checkPacket.toByteArray(), "CheckPacket")) return false
        delay(bleOperationInterval)
        return true
    }
    
    private suspend fun sendWritePacket(packet: ArrayList<ArrayList<Byte>>, packetsPerTransfer: Int): Boolean
    {
        do
        {
            log("Remaining bytes: $remainingBytes")
            remainingBytes -= packet[packetIndex].size
            if(!sendPacket(packet[packetIndex++].toByteArray(), "WritePacket")) return false
        }
        while(packetIndex % packetsPerTransfer != 0 && packetIndex != packet.size)
        delay(bleOperationInterval)
        return true
    }
    
    private suspend fun sendReadInfoPacket(payload: ByteArray): Boolean
    {
        // Build ReadInfo packet
        val readINFO = ArrayList<Byte>(List(MCU_PACKET_SIZE - CRC_SIZE - 1) { 0xFF.toByte() })   // Control Byte: 1 byte
        for(i in payload.indices)
        {
            readINFO[i] = payload[i]
        }
        val crcBytes = generateCRC32(readINFO)
        readINFO.addAll(0, crcBytes)
        readINFO.add(0, MCU_READ.toByte())
        
        if(!sendPacket(readINFO.toByteArray(), "ReadInfoPacket")) return false
        delay(bleOperationInterval)
        return true
    }
    
    private suspend fun sendPacket(packet: ByteArray, packetName: String): Boolean
    {
        if(!ConnectionManager.writeCharacteristic(device, characteristic, packet))
        {
            log("[Error] Failed to send $packetName")
            errorCode = BLE_WRITE_ERROR
            return false
        }
        
        return true
    }
    
    private fun organizeReadCMDPacket(): ByteArray
    {
        val readCMD = ArrayList<Byte>(List(MCU_PACKET_SIZE - CRC_SIZE - 1) { 0xFF.toByte() })   // Control Byte: 1 byte
        val crcBytes = generateCRC32(readCMD)
        readCMD.addAll(0, crcBytes)
        readCMD.add(0, MCU_READ.toByte())
        return readCMD.toByteArray()
    }
    
    private suspend fun verifyStatusPacket(): Boolean
    {
        if(waitCharacteristicChanged(waitingNotificationTime))
        {
            // DEBUG
//            log("ReceivedStatusPacket, ${receivedBuffer.toHexString()}")
            
            // check packet size
            if(receivedBuffer.size % MCU_PACKET_SIZE != 0)
            {
                errorCode = errorCode or MCU_PACKET_SIZE_UNMATCHED
                log("[Error] MCU packet size is not multiple of $MCU_PACKET_SIZE")
                shouldRetry = false
                return false
            }
            
            // check CRC32
            if(!verifyCRC32(receivedBuffer))
            {
                errorCode = errorCode or API_CRC_ERROR
                log("[Warning] CRC32 is incorrect from API, times : ${retryTimes_CRC_ERROR++}")
                shouldRetry = retryTimes_CRC_ERROR < maxRetryTimes_CRC_ERROR
                if(!shouldRetry) log("[Error] Max retries exceeded!")
                return shouldRetry
            }
            
            val statusByte = receivedBuffer[CRC_SIZE].toInt()
            
            if(statusByte == MCU_STATE_OK)
            {
                errorCode = MCU_STATE_OK
                retryTimes_MCU_BUSY = 0
                retryTimes_CRC_ERROR = 0
                shouldRetry = false
                return true
            }
            else
            {
                if(statusByte and MCU_STATE_BUSY != 0)
                {
                    errorCode = errorCode or MCU_STATE_BUSY
                    log("[Warning] MCU is busy, times : ${retryTimes_MCU_BUSY++}")
                    shouldRetry = retryTimes_MCU_BUSY < maxRetryTimes_MCU_BUSY
                    if(!shouldRetry) log("[Error] Max retries exceeded!")
                    return shouldRetry
                }
                if(statusByte and MCU_STATE_CRC_ERROR != 0)
                {
                    errorCode = errorCode or MCU_STATE_CRC_ERROR
                    log("[Warning] CRC32 is incorrect from MCU, times : ${retryTimes_CRC_ERROR++}")
                    shouldRetry = retryTimes_CRC_ERROR < maxRetryTimes_CRC_ERROR
                    if(!shouldRetry) log("[Error] Max retries exceeded!")
                    return shouldRetry
                }
                if(statusByte and MCU_STATE_CMD_ERROR != 0)
                {
                    errorCode = errorCode or MCU_STATE_CMD_ERROR
                    log("[Error] MCU command error")
                    shouldRetry = false
                    return false
                }
                // Unexpected error
                if(statusByte and MCU_STATE_BUSY and MCU_STATE_CRC_ERROR and MCU_STATE_CMD_ERROR != 0)
                {
                    errorCode = receivedBuffer[CRC_SIZE].toInt()
                    log("[Error] Unexpected error! Error code: ${String.format("%02X", errorCode)}")
                    shouldRetry = false
                    return false
                }
                
                return false
            }
        }
        else
        {
            errorCode = BLE_TIME_OUT
            log("[Error] BLE Time out, no response")
            shouldRetry = false
            return false
        }
    }
    
    private suspend fun verifyDataPacket(): Boolean
    {
        if(waitCharacteristicChanged(waitingNotificationTime))
        {
            // DEBUG
//            log("ReceivedStatusPacket, ${receivedBuffer.toHexString()}")
            
            // check packet size
            if(receivedBuffer.size % MCU_PACKET_SIZE != 0)
            {
                errorCode = errorCode or MCU_PACKET_SIZE_UNMATCHED
                log("[Error] MCU packet size is not multiple of $MCU_PACKET_SIZE")
                shouldRetry = false
                return false
            }
            
            val statusByte = receivedBuffer[CRC_SIZE].toInt()
            
            if(statusByte == MCU_STATE_OK)
            {
                errorCode = MCU_STATE_OK
                retryTimes_MCU_BUSY = 0
                retryTimes_CRC_ERROR = 0
                shouldRetry = false
                return true
            }
            else
            {
                if(statusByte and MCU_STATE_BUSY != 0)
                {
                    errorCode = errorCode or MCU_STATE_BUSY
                    log("[Warning] MCU is busy, times : ${retryTimes_MCU_BUSY++}")
                    shouldRetry = retryTimes_MCU_BUSY < maxRetryTimes_MCU_BUSY
                    if(!shouldRetry) log("[Error] Max retries exceeded!")
                }
                if(statusByte and MCU_STATE_CRC_ERROR != 0)
                {
                    errorCode = errorCode or MCU_STATE_CRC_ERROR
                    log("[Warning] CRC32 is incorrect from MCU, times : ${retryTimes_CRC_ERROR++}")
                    shouldRetry = retryTimes_CRC_ERROR < maxRetryTimes_CRC_ERROR
                    if(!shouldRetry) log("[Error] Max retries exceeded!")
                }
                if(statusByte and MCU_STATE_CMD_ERROR != 0)
                {
                    errorCode = errorCode or MCU_STATE_CMD_ERROR
                    log("[Error] MCU command error")
                    shouldRetry = false
                }
                // Unexpected error
                if(statusByte and MCU_STATE_BUSY and MCU_STATE_CRC_ERROR and MCU_STATE_CMD_ERROR != 0)
                {
                    errorCode = receivedBuffer[CRC_SIZE].toInt()
                    log("[Error] Unexpected error! Error code: ${String.format("%02X", errorCode)}")
                    shouldRetry = false
                }
                
                return false
            }
        }
        else
        {
            errorCode = BLE_TIME_OUT
            log("[Error] BLE Time out, no response")
            shouldRetry = false
            return false
        }
    }
    
    private suspend fun checkMcuState(): Boolean
    {
        if(!sendCheckPacket()) return false
        if(!verifyStatusPacket()) return false
        
        return true
    }
    
    private suspend fun checkMcuStateEx(): Boolean
    {
        do
        {
            checkMcuState()
            if(retryTimes_MCU_BUSY >= maxRetryTimes_MCU_BUSY || retryTimes_CRC_ERROR >= maxRetryTimes_CRC_ERROR) return false
        }
        while(errorCode != MCU_STATE_OK);
        
        
        var currentTimes = 0
        
        while(currentTimes < maxRetryTimes_MCU_BUSY)
        {
            if(checkMcuState())
            {
                return true
            }
            else if(errorCode == MCU_STATE_BUSY)
            {
                log("[Warning] [Check State] MCU busy, times : ${++currentTimes}")
            }
            else if(errorCode == BLE_TIME_OUT)
            {
                log("[Warning] [Check State] Time out, no response, times : ${++currentTimes}")
            }
            else if(errorCode == API_CRC_ERROR)
            {
                log("[Warning] [Check State] CRC32 check error")
                return false
            }
            else if(errorCode == MCU_PACKET_SIZE_UNMATCHED)
            {
                log("[Error] [Check State] MCU packet size is less than 64")
                return false
            }
            else
            {
                log("[Error] [Check State] Unexpected error ! Error code: ${String.format("%02X", errorCode)}")
                return false
            }
            
            delay(checkIntervalTime) // check state interval time
        }
        
        log("[Error] [Check State] Max retries exceeded !")
        return false
    }
    
    private fun buildWritePackets(sourceData: ByteArray): ArrayList<ArrayList<Byte>>
    {
        val writePacket: ArrayList<ArrayList<Byte>> = ArrayList()
        val tempPacket = ArrayList<Byte>()
        var isFirstPacket = true
        
        for((sourceDataIndex, byteData) in sourceData.withIndex())
        {
            tempPacket.add(byteData)
            // 1st. write operation
            if(tempPacket.size == MCU_BUFFER_SIZE + WRITE_CMD_SIZE && isFirstPacket)
            {
                isFirstPacket = false
                organizeWritePacket(writePacket, tempPacket)
            }
            // 2nd. ~ N th. write operation
            else if(tempPacket.size == MCU_BUFFER_SIZE && !isFirstPacket)
            {
                organizeWritePacket(writePacket, tempPacket)
            }
            else if(sourceDataIndex == sourceData.size - 1)
            {
                organizeWritePacket(writePacket, tempPacket)
            }
            
        }
        return writePacket
    }
    
    private fun organizeWritePacket(writePacket: ArrayList<ArrayList<Byte>>, tempPacket: ArrayList<Byte>)
    {
        var dummySize = 0
        val payloadSize = tempPacket.size + CRC_SIZE + 1
        // Padding with dummy based on 64
        if(payloadSize % MCU_PACKET_SIZE != 0) dummySize = MCU_PACKET_SIZE - (payloadSize % MCU_PACKET_SIZE)
        
        for(i in 0 until dummySize)
        {
            tempPacket.add(0xFF.toByte())
        }
        
        // Generate CRC32 value
        val crcBytes: ArrayList<Byte> = generateCRC32(tempPacket)
        tempPacket.addAll(0, crcBytes)
        tempPacket.add(0, MCU_WRITE.toByte())
        
        for(dataIndex in 0 until tempPacket.size step BLE_TRANSFER_SIZE)
        {
            val endIndex = if(tempPacket.size > dataIndex + BLE_TRANSFER_SIZE) dataIndex + BLE_TRANSFER_SIZE else tempPacket.size
            writePacket.add(ArrayList(tempPacket.subList(dataIndex, endIndex)))
        }
        
        tempPacket.clear()
    }
    
    private suspend fun getReceivedData(data: ArrayList<Byte>): Boolean
    {
        remainingBytes = requestedDataLength - data.size
        val tempBuffer = ArrayList<Byte>()
        var dummySize = 0
        var maxDataLength = 0
        
        log("Remaining data length: $remainingBytes.")
        // Remaining data length > 1020
        if(remainingBytes > MCU_BUFFER_SIZE - CRC_SIZE - STATUS_SIZE)
        {
            //// Get data via 3 notifications
            if(!getDataInSegments(tempBuffer, 3))
            {
                return shouldRetry
            }
            maxDataLength = if(remainingBytes > MCU_BUFFER_SIZE) MCU_BUFFER_SIZE else remainingBytes
        }
        // 508 < Remaining data length <= 1020
        else if(((BLE_TRANSFER_SIZE - CRC_SIZE - STATUS_SIZE) < remainingBytes) && (remainingBytes <= (MCU_BUFFER_SIZE - CRC_SIZE - STATUS_SIZE)))
        {
            //// Get data via 2 notifications
            if(!getDataInSegments(tempBuffer, 2))
            {
                return shouldRetry
            }
            maxDataLength = remainingBytes
        }
        // Remaining data length <= 508
        else
        {
            //// Get data via 1 notification
            if(!getDataInSegments(tempBuffer, 1))
            {
                return shouldRetry
            }
            maxDataLength = remainingBytes
        }
        
        // Check CRC32
        if(verifyCRC32(tempBuffer))
        {
            if((maxDataLength + CRC_SIZE + STATUS_SIZE) % MCU_PACKET_SIZE != 0) dummySize = MCU_PACKET_SIZE - ((maxDataLength + CRC_SIZE + STATUS_SIZE) % MCU_PACKET_SIZE)
            else dummySize = 0
            // Remove dummy and CRC bytes
            data.addAll(tempBuffer.subList(CRC_SIZE + STATUS_SIZE, tempBuffer.size - dummySize))
            return true
        }
        else
        {
            errorCode = errorCode or API_CRC_ERROR
            log("[Warning] CRC32 is incorrect from API, times : ${retryTimes_CRC_ERROR++}")
            shouldRetry = retryTimes_CRC_ERROR < maxRetryTimes_CRC_ERROR
            if(!shouldRetry) log("[Error] Max retries exceeded!")
            return shouldRetry
        }
    }
    
    private suspend fun getDataInSegments(tempBuffer: ArrayList<Byte>, times: Int): Boolean
    {
        for(i in 0 until times)
        {
            if(i == 0)    // First time
            {
                if(!verifyDataPacket()) return false
            }
            else
            {
                if(!waitCharacteristicChanged(waitingNotificationTime))
                {
                    errorCode = BLE_TIME_OUT
                    log("[Error] [Read] Time out at no.${i + 1} transaction.")
                    shouldRetry = false
                    return false
                }
            }
            tempBuffer.addAll(receivedBuffer)
            
        }
        
        return true
    }
    
    private suspend fun waitCharacteristicChanged(waitingTime: Long): Boolean
    {
        try
        {
            withTimeout(waitingTime) {
                while(!receivedNotification)
                {
                    delay(1)
                }
            }
            setNotificationTrigger(false)
            return true
        }
        catch(e: Exception)
        {
            // timeout
            return false
        }
    
    
//        val startTime = System.currentTimeMillis()
//
//        while(!receivedNotification)
//        {
//            if(System.currentTimeMillis() - startTime > waitingTime)
//            {
//                break
//            }
//        }
//
//        if(receivedNotification)
//        {
//            setNotificationTrigger(false)
//            return true
//        }
//        else // timeout
//            return false
//
//
    }
    
    private fun generateCRC32(data: ArrayList<Byte>): ArrayList<Byte>
    {
        val crc = CRC32()
        crc.update(data.toByteArray())
        
        val crcBytes = ArrayList<Byte>(4)
        crcBytes.add((crc.value shr 24).toByte())
        crcBytes.add((crc.value shr 16).toByte())
        crcBytes.add((crc.value shr 8).toByte())
        crcBytes.add(crc.value.toByte())

//        var temp = ""
//        for(value in data)
//        {
//            temp += String.format("%02X ", value)
//        }
//        Timber.e("CRC Test Data(HEX): ${temp}")
//
//        temp = ""
//        for(value in crcBytes)
//        {
//            temp += String.format("%02X ", value)
//        }
//        Timber.e("CRC calculated values(Hex): ${temp}")
//        Timber.e("CRC calculated values(Dec) ${crc.value}")
        
        return crcBytes
    }
    
    private fun verifyCRC32(data: ArrayList<Byte>): Boolean
    {
        val receivedCRCBytes = ArrayList<Byte>(data.subList(0, CRC_SIZE))
        val pureData = ArrayList<Byte>(data.subList(CRC_SIZE, data.size))
        val crcBytes = generateCRC32(pureData)
        for(i in 0 until CRC_SIZE)
        {
            if(crcBytes[i] != receivedCRCBytes[i])
            {
                return false
            }
        }
        
        return true
    }
    
    fun initActivity(activity: AppCompatActivity)
    {
        this.activity = activity
    }
    
    private fun log(message: String)
    {
        val dateFormatter = SimpleDateFormat("HH:mm:ss", Locale.TAIWAN)
        val formattedMessage = String.format("%s: %s", dateFormatter.format(Date()), message)
        val textViewLog = McuProtocol.activity.findViewById<TextView>(R.id.log_text_view)
        val scrollViewLog = activity.findViewById<ScrollView>(R.id.log_scroll_view)
        activity.runOnUiThread {
            
            val currentLogText = textViewLog.text
            textViewLog.text = "$currentLogText\n$formattedMessage"
            scrollViewLog.post { scrollViewLog.fullScroll(View.FOCUS_DOWN) }
        }
    }
    
    
    fun setDataLength(length: Int)
    {
        requestedDataLength = length
    }
    
    fun setNotificationTrigger(value: Boolean)
    {
        receivedNotification = value
    }
    
    fun setReceivedData(payload: ArrayList<Byte>)
    {
        receivedBuffer = payload
    }

//    fun ArrayList<Byte>.toHexString(): String =
//        joinToString(separator = " ", prefix = "") { String.format("%02X", it) }
    
    private fun ArrayList<Byte>.toHexString(): String
    {
        val builder = StringBuilder()
        builder.append("Total Bytes: ${this.size}")
        for(i in indices)
        {
            if(i % 16 == 0)
            {
                builder.append("\n") // 換行，每行 16 個字節
            }
            builder.append(String.format("%02X", this[i]))
            builder.append("\t") // 添加空格分隔字節
        }
        return builder.toString()
    }
    
    fun getErrorCode(): Int
    {
        return errorCode
    }
    
    private fun buildTestPacket(payload: ByteArray): ByteArray
    {
        var length = 0
        for(element in payload)
        {
            length = length shl 8 or (element.toInt() and 0xFF)
        }
        log("MCU Write is executing. Input bytes: $length") // TODO mark
        return byteArrayOf(*payload, 0xD0.toByte(), 0xD1.toByte(), *ByteArray(length - payload.size - 2) { i -> (i % 256).toByte() })
    }
    
}