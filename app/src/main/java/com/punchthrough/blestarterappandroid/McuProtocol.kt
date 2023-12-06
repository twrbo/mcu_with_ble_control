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
import kotlin.experimental.or

object McuProtocol
{
    // Size
    private const val MCU_BUFFER_SIZE: Int = 1024
    private const val MCU_PACKET_SIZE: Int = 64
    private const val BLE_TRANSFER_SIZE: Int = 512
    private const val CRC_SIZE: Int = 4
    private const val STATUS_SIZE: Int = 1
    
    // ------------------------------------------- MCU Protocol Defined -------------------------------------------- //
    // MCU Control Byte
    private const val MCU_READ = 0x04
    private const val MCU_WRITE = 0x00
    private const val MCU_CHECK = 0x10
    private const val CRC_ERROR = 0x20
    
    // MCU State
    private const val MCU_STATE_OK = 0x00
    private const val MCU_STATE_BUSY = 0x01
    private const val MCU_STATE_CMD_ERROR = 0x10
    private const val MCU_STATE_CRC_ERROR = 0x20
    
    // ----------------------------------------------- API Defined ------------------------------------------------- //
    private const val BLE_WRITE_ERROR = 0x100
    private const val BLE_READ_ERROR = 0x200
    private const val BLE_TIME_OUT = 0x400
    private const val MCU_PACKET_SIZE_UNMATCHED = 0x800
    private const val API_CRC_ERROR = 0x1000
    
    // ------------------------------------ Interval Time & Retry Times -------------------------------------------- //
    private var waitingNotificationTime: Long = 5000
    private var responseTimeNormal: Long = 1
    private var responseTimeWriteCMD: Long = 50
    private var responseTimeReadCMD: Long = 1
    private var queryIntervalNormal: Long = 1
    private var queryIntervalRetry: Long = 1
    private var writePacketInterval: Long = 1
    
    private var maxRetryTimes_MCU_BUSY: Int = 3
    private var maxRetryTimes_CRC_ERROR: Int = 3
    
    // ------------------------------------------ Other Parameters ------------------------------------------------- //
    private var characteristicValue: ArrayList<Byte> = ArrayList<Byte>()
    private var receivedNotification: Boolean = false
    private var readLength: Int = 0
    private var opcodeLength: Int = 7
    private var errorCode = 0
    
    private var writePacketIndex = 0
    private var remainingBytes = 0
    private var retryTimes_MCU_BUSY = 0
    private var retryTimes_CRC_ERROR = 0
    private var shouldRetry = false
    
    private lateinit var activity: AppCompatActivity
    private lateinit var device: BluetoothDevice
    private lateinit var characteristic: BluetoothGattCharacteristic
    // ------------------------------------------------------------------------------------------------------------ //
    
    suspend fun write(bluetoothDevice: BluetoothDevice, bluetoothGattCharacteristic: BluetoothGattCharacteristic,
                      payload: ByteArray): Boolean
    {
        device = bluetoothDevice
        characteristic = bluetoothGattCharacteristic
        errorCode = 0
        retryTimes_MCU_BUSY = 0
        retryTimes_CRC_ERROR = 0
        remainingBytes = 0
        writePacketIndex = 0
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
//            val writePacket = buildWritePackets(payload)
//            log("MCU Write is executing. Input bytes: ${payload.size}")
            // Check status
            do
            {
                if(!sendCheckPacket()) return false
                delay(responseTimeNormal)
                if(!verifyStatusPacket()) return false
    
                if(shouldRetry) delay(queryIntervalRetry)
                else delay(queryIntervalNormal)
            }
            while(shouldRetry)
            
            // Write packet
            for(data in writePacket) remainingBytes += data.size
            
            while(writePacketIndex < writePacket.size)
            {
                do
                {
                    if(!sendWritePacket(writePacket)) return false
                    delay(responseTimeWriteCMD)
                    if(!verifyStatusPacket()) return false
                    
                    if(shouldRetry)
                    {
                        delay(queryIntervalRetry)
                    }
                    else delay(queryIntervalNormal)
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
    
    suspend fun read(bluetoothDevice: BluetoothDevice, bluetoothGattCharacteristic: BluetoothGattCharacteristic,
                     payload: ByteArray): Boolean
    {
        device = bluetoothDevice
        characteristic = bluetoothGattCharacteristic
        errorCode = 0
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
            
            // Check status
            do
            {
                if(!sendCheckPacket()) return false
                delay(responseTimeNormal)
                if(!verifyStatusPacket()) return false
                
                if(shouldRetry) delay(queryIntervalRetry)
                else delay(queryIntervalNormal)
            }
            while(shouldRetry)
            
            // Send ReadInfo packet
            do
            {
                if(!sendReadInfoPacket(payload)) return false
                delay(responseTimeNormal)
                if(!verifyStatusPacket()) return false
                
                if(shouldRetry) delay(queryIntervalRetry)
                else delay(queryIntervalNormal)
            }
            while(shouldRetry)
            
            log("MdCU Read is executing. Request length: $readLength")
            while(data.size < readLength)
            {
                // Check status
                do
                {
                    if(!sendCheckPacket()) return false
                    delay(responseTimeNormal)
                    if(!verifyStatusPacket()) return false
                    
                    if(shouldRetry) delay(queryIntervalRetry)
                    else delay(queryIntervalNormal)
                }
                while(shouldRetry)
                
                // Send ReadCMD packet
                do
                {
                    if(!sendReadCMDPacket()) return false
                    delay(responseTimeReadCMD)
                    if(!verifyDataPacket(data)) return false
                    
                    if(shouldRetry) delay(queryIntervalRetry)
                    else delay(queryIntervalNormal)
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
        val checkPacket = ArrayList<Byte>(
            List(MCU_PACKET_SIZE - CRC_SIZE - 1) { 0xFF.toByte() })   // Control Byte: 1 byte
        val crcBytes = generateCRC32(checkPacket)
        // Put CRC32 Bytes after Control Byte
        checkPacket.addAll(0, crcBytes)
        checkPacket.add(0, MCU_CHECK.toByte())
        
        if(!sendPacket(checkPacket.toByteArray(), "CheckPacket")) return false
//        delay(bleOperationInterval)
        return true
    }
    
    private suspend fun sendWritePacket(packet: ArrayList<ArrayList<Byte>>): Boolean
    {
        var transactionTimes = 0
        val packetsPerTransfer = 3
        
        // Undo the packets
        if(shouldRetry)
        {
            val revertTimes = if(writePacketIndex % packetsPerTransfer == 0) 3 else writePacketIndex % packetsPerTransfer
            writePacketIndex -= revertTimes
            for(i in 0 until revertTimes) remainingBytes += packet[writePacketIndex + i].size // for log message
    
            // Add control byte for MCU retry mechanism
            if(errorCode and API_CRC_ERROR != 0)
            {
                packet[writePacketIndex][0] = packet[writePacketIndex][0] or CRC_ERROR.toByte()
            }
        }
        
        
        while(transactionTimes != packetsPerTransfer && writePacketIndex != packet.size)
        {
            log("Remaining bytes: $remainingBytes")
            remainingBytes -= packet[writePacketIndex].size
            if(!ConnectionManager.writeCharacteristic(device, characteristic, packet[writePacketIndex++].toByteArray()))
            {
                log("[Error] Failed to send WritePacket")
                errorCode = BLE_WRITE_ERROR
                return false
            }
            transactionTimes++
            delay(writePacketInterval)
        }
//        delay(bleOperationInterval)
        return true
    }
    
    private suspend fun sendReadInfoPacket(payload: ByteArray): Boolean
    {
        val readINFO = ArrayList<Byte>(List(MCU_PACKET_SIZE - CRC_SIZE - 1) { 0xFF.toByte() })   // Control Byte: 1 byte
        for(i in payload.indices)
        {
            readINFO[i] = payload[i]
        }
        val crcBytes = generateCRC32(readINFO)
        readINFO.addAll(0, crcBytes)
        readINFO.add(0, MCU_READ.toByte())
        
        if(!sendPacket(readINFO.toByteArray(), "ReadInfoPacket")) return false
//        delay(bleOperationInterval)
        return true
    }
    
    private suspend fun sendReadCMDPacket(): Boolean
    {
        val readCMD = ArrayList<Byte>(List(MCU_PACKET_SIZE - CRC_SIZE - 1) { 0xFF.toByte() })   // Control Byte: 1 byte
        val crcBytes = generateCRC32(readCMD)
        readCMD.addAll(0, crcBytes)
        readCMD.add(0, MCU_READ.toByte())
        
        if(!sendPacket(readCMD.toByteArray(), "ReadCMDPacket")) return false
//        delay(bleOperationInterval)
        return true
    }
    
    private suspend fun sendPacket(packet: ByteArray, packetName: String): Boolean
    {
        // Add control byte for MCU retry mechanism
        if(errorCode and API_CRC_ERROR != 0) packet[0] = packet[0] or CRC_ERROR.toByte()
        
        if(!ConnectionManager.writeCharacteristic(device, characteristic, packet))
        {
            log("[Error] Failed to send $packetName")
            errorCode = BLE_WRITE_ERROR
            return false
        }
        
        return true
    }
    
    
    private suspend fun verifyStatusPacket(): Boolean
    {
        if(waitNotification(waitingNotificationTime))
        {
            // DEBUG
//            log("ReceivedStatusPacket, ${characteristicValue.toHexString()}")
            
            // check packet size
            if(characteristicValue.size % MCU_PACKET_SIZE != 0)
            {
                log("[Error] MCU packet size is not multiple of $MCU_PACKET_SIZE")
                errorCode = errorCode or MCU_PACKET_SIZE_UNMATCHED
                shouldRetry = false
            }
            
            
            // check CRC32
            if(!verifyCRC32(characteristicValue))
            {
                log("[Warning] CRC32 is incorrect from API, times : ${++retryTimes_CRC_ERROR}")
                errorCode = errorCode or API_CRC_ERROR
                shouldRetry = retryTimes_CRC_ERROR < maxRetryTimes_CRC_ERROR
                if(!shouldRetry) log("[Error] Max retries exceeded!")
                return shouldRetry
            }
            
            val statusByte = characteristicValue[CRC_SIZE].toInt()
            
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
                    log("[Warning] MCU is busy, times : ${++retryTimes_MCU_BUSY}")
                    errorCode = errorCode or MCU_STATE_BUSY
                    shouldRetry = retryTimes_MCU_BUSY < maxRetryTimes_MCU_BUSY
                    if(!shouldRetry) log("[Error] Max retries exceeded!")
                }
                if(statusByte and MCU_STATE_CRC_ERROR != 0)
                {
                    log("[Warning] CRC32 is incorrect from MCU, times : ${++retryTimes_CRC_ERROR}")
                    errorCode = errorCode or MCU_STATE_CRC_ERROR
                    shouldRetry = retryTimes_CRC_ERROR < maxRetryTimes_CRC_ERROR
                    if(!shouldRetry) log("[Error] Max retries exceeded!")
                }
                if(statusByte and MCU_STATE_CMD_ERROR != 0)
                {
                    log("[Error] MCU command error")
                    errorCode = errorCode or MCU_STATE_CMD_ERROR
                }
                // Unexpected error
                if(statusByte and (MCU_STATE_BUSY or MCU_STATE_CRC_ERROR or MCU_STATE_CMD_ERROR) == 0)
                {
                    errorCode = characteristicValue[CRC_SIZE].toInt()
                    log("[Error] Unexpected error! Error code: ${String.format("%02X", errorCode)}")
                }
            }
        }
        else
        {
            log("[Error] BLE Time out, no response")
            errorCode = BLE_TIME_OUT
            shouldRetry = false
        }
        
        return shouldRetry
    }
    
    private suspend fun verifyStatusPacket(characteristicValue: ArrayList<Byte>): Boolean
    {
        // For verifyDataPacket calling, just verify the characteristicValue
        // 1. No waiting mechanism
        // 2. return statement is different from verifyStatusPacket()
        
        if(characteristicValue.size % MCU_PACKET_SIZE != 0)
        {
            log("[Error] MCU packet size is not multiple of $MCU_PACKET_SIZE")
            errorCode = errorCode or MCU_PACKET_SIZE_UNMATCHED
            shouldRetry = false
        }
        
        
        // check CRC32
        if(!verifyCRC32(characteristicValue))
        {
            log("[Warning] CRC32 is incorrect from API, times : ${++retryTimes_CRC_ERROR}")
            errorCode = errorCode or API_CRC_ERROR
            shouldRetry = retryTimes_CRC_ERROR < maxRetryTimes_CRC_ERROR
            if(!shouldRetry) log("[Error] Max retries exceeded!")
            return false
        }
        
        val statusByte = characteristicValue[CRC_SIZE].toInt()
        
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
                log("[Warning] MCU is busy, times : ${++retryTimes_MCU_BUSY}")
                errorCode = errorCode or MCU_STATE_BUSY
                shouldRetry = retryTimes_MCU_BUSY < maxRetryTimes_MCU_BUSY
                if(!shouldRetry) log("[Error] Max retries exceeded!")
            }
            if(statusByte and MCU_STATE_CRC_ERROR != 0)
            {
                log("[Warning] CRC32 is incorrect from MCU, times : ${++retryTimes_CRC_ERROR}")
                errorCode = errorCode or MCU_STATE_CRC_ERROR
                shouldRetry = retryTimes_CRC_ERROR < maxRetryTimes_CRC_ERROR
                if(!shouldRetry) log("[Error] Max retries exceeded!")
            }
            if(statusByte and MCU_STATE_CMD_ERROR != 0)
            {
                log("[Error] MCU command error")
                errorCode = errorCode or MCU_STATE_CMD_ERROR
            }
            // Unexpected error
            if(statusByte and (MCU_STATE_BUSY or MCU_STATE_CRC_ERROR or MCU_STATE_CMD_ERROR) == 0)
            {
                errorCode = characteristicValue[CRC_SIZE].toInt()
                log("[Error] Unexpected error! Error code: ${String.format("%02X", errorCode)}")
            }
        }
        
        return false
    }
    
    private suspend fun verifyDataPacket(): Boolean
    {
        if(waitNotification(waitingNotificationTime))
        {
            // DEBUG
//            log("ReceivedStatusPacket, ${characteristicValue.toHexString()}")
            
            // check packet size
            if(characteristicValue.size % MCU_PACKET_SIZE != 0)
            {
                log("[Error] MCU packet size is not multiple of $MCU_PACKET_SIZE")
                errorCode = errorCode or MCU_PACKET_SIZE_UNMATCHED
                shouldRetry = false
            }
            
            // check CRC32
            if(!verifyCRC32(characteristicValue))
            {
                log("[Warning] CRC32 is incorrect from API, times : ${++retryTimes_CRC_ERROR}")
                errorCode = errorCode or API_CRC_ERROR
                shouldRetry = retryTimes_CRC_ERROR < maxRetryTimes_CRC_ERROR
                if(!shouldRetry) log("[Error] Max retries exceeded!")
                return false
            }
            
            val statusByte = characteristicValue[CRC_SIZE].toInt()
            
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
                    log("[Warning] MCU is busy, times : ${++retryTimes_MCU_BUSY}")
                    errorCode = errorCode or MCU_STATE_BUSY
                    shouldRetry = retryTimes_MCU_BUSY < maxRetryTimes_MCU_BUSY
                    if(!shouldRetry) log("[Error] Max retries exceeded!")
                }
                if(statusByte and MCU_STATE_CRC_ERROR != 0)
                {
                    log("[Warning] CRC32 is incorrect from MCU, times : ${++retryTimes_CRC_ERROR}")
                    errorCode = errorCode or MCU_STATE_CRC_ERROR
                    shouldRetry = retryTimes_CRC_ERROR < maxRetryTimes_CRC_ERROR
                    if(!shouldRetry) log("[Error] Max retries exceeded!")
                }
                if(statusByte and MCU_STATE_CMD_ERROR != 0)
                {
                    log("[Error] MCU command error")
                    errorCode = errorCode or MCU_STATE_CMD_ERROR
                }
                // Unexpected error
                if(statusByte and (MCU_STATE_BUSY or MCU_STATE_CRC_ERROR or MCU_STATE_CMD_ERROR) == 0)
                {
                    errorCode = characteristicValue[CRC_SIZE].toInt()
                    log("[Error] Unexpected error! Error code: ${String.format("%02X", errorCode)}")
                }
            }
        }
        else
        {
            log("[Error] BLE Time out, no response")
            errorCode = BLE_TIME_OUT
            shouldRetry = false
        }
        
        return false
    }
    
    private suspend fun verifyDataPacket(data: ArrayList<Byte>): Boolean
    {
        remainingBytes = readLength - data.size
        val tempBuffer = ArrayList<Byte>()
        var dummySize = 0
        var maxDataLength = 0
        
        // Get data via BLE notifications
        log("Remaining data length: $remainingBytes.")
        if(remainingBytes > MCU_BUFFER_SIZE - CRC_SIZE - STATUS_SIZE)   // Remaining data length > 1019
        {
            //// Get data via 3 BLE notifications
            if(!getDataInSegments(tempBuffer, 3))
            {
                return false
            }
            maxDataLength = if(remainingBytes > MCU_BUFFER_SIZE) MCU_BUFFER_SIZE else remainingBytes
        }
        else if(((BLE_TRANSFER_SIZE - CRC_SIZE - STATUS_SIZE) < remainingBytes) && (remainingBytes <= (MCU_BUFFER_SIZE - CRC_SIZE - STATUS_SIZE))) // 507 < Remaining data length <= 1019
        {
            //// Get data via 2 BLE notifications
            if(!getDataInSegments(tempBuffer, 2))
            {
                return false
            }
            maxDataLength = remainingBytes
        }
        else    // Remaining data length <= 507
        {
            //// Get data via 1 BLE notification
            if(!getDataInSegments(tempBuffer, 1))
            {
                return false
            }
            maxDataLength = remainingBytes
        }
        
        if(verifyStatusPacket(tempBuffer))
        {
            // Remove dummy and CRC bytes
            if((maxDataLength + CRC_SIZE + STATUS_SIZE) % MCU_PACKET_SIZE != 0) dummySize = MCU_PACKET_SIZE - ((maxDataLength + CRC_SIZE + STATUS_SIZE) % MCU_PACKET_SIZE)
            else dummySize = 0
            data.addAll(tempBuffer.subList(CRC_SIZE + STATUS_SIZE, tempBuffer.size - dummySize))
            return true
        }
        else return shouldRetry
    }
    
    private suspend fun getDataInSegments(tempBuffer: ArrayList<Byte>, times: Int): Boolean
    {
        for(i in 0 until times)
        {
            if(!waitNotification(waitingNotificationTime))
            {
                errorCode = BLE_TIME_OUT
                log("[Error] Time out at no.${i + 1} transaction.")
                shouldRetry = false
                return false
            }
            tempBuffer.addAll(characteristicValue)
            
            /*
                        if(i == 0)    // First time
            {
                if(!verifyDataPacket()) return false
            }
            else
            {
                if(!waitNotification(waitingNotificationTime))
                {
                    errorCode = BLE_TIME_OUT
                    log("[Error] [Read] Time out at no.${i + 1} transaction.")
                    shouldRetry = false
                    return false
                }
            }
            
             */
        }
        
        return true
    }
    
    
    private fun buildWritePackets(payload: ByteArray): ArrayList<ArrayList<Byte>>
    {
        val writePacket: ArrayList<ArrayList<Byte>> = ArrayList()
        val tempPacket = ArrayList<Byte>()
        var isFirstPacket = true
        
        for((index, byteData) in payload.withIndex())
        {
            tempPacket.add(byteData)
            
            when
            {
                tempPacket.size == MCU_BUFFER_SIZE + opcodeLength && isFirstPacket ->
                {
                    isFirstPacket = false
                    organizeWritePacket(writePacket, tempPacket)
                }
                
                tempPacket.size == MCU_BUFFER_SIZE && !isFirstPacket ->
                {
                    organizeWritePacket(writePacket, tempPacket)
                }
                
                index == payload.size - 1 ->
                {
                    organizeWritePacket(writePacket, tempPacket)
                }
                
                else ->
                {
                    // Do nothing. Only one of the above statement will be true
                }
            }
            
            /*
            if(tempPacket.size == MCU_BUFFER_SIZE + opcodeLength && isFirstPacket) // 1st. write operation
            {
                isFirstPacket = false
                organizeWritePacket(writePacket, tempPacket)
            }
            else if(tempPacket.size == MCU_BUFFER_SIZE && !isFirstPacket) // 2nd. ~ N-1 th. write operation
            {
                organizeWritePacket(writePacket, tempPacket)
            }
            else if(index == payload.size - 1) // Nth. write operation
            {
                organizeWritePacket(writePacket, tempPacket)
            }
             */
        }
        return writePacket
    }
    
    private fun organizeWritePacket(writePacket: ArrayList<ArrayList<Byte>>, tempPacket: ArrayList<Byte>)
    {
        var dummySize = 0
        val payloadSize = tempPacket.size + CRC_SIZE + 1    // 1: Control byte(MCU_Write) size
        
        // Padding with dummy based on MCU_PACKET_SIZE(64)
        if(payloadSize % MCU_PACKET_SIZE != 0) dummySize = MCU_PACKET_SIZE - (payloadSize % MCU_PACKET_SIZE)
        for(i in 0 until dummySize)
        {
            tempPacket.add(0xFF.toByte())
        }
        
        // Generate CRC32 value
        val crcBytes: ArrayList<Byte> = generateCRC32(tempPacket)
        
        // Add CRC bytes and control bytes
        tempPacket.addAll(0, crcBytes)
        tempPacket.add(0, MCU_WRITE.toByte())
        
        // Separate the payload based on BLE_TRANSFER_SIZE(512)
        for(startIndex in 0 until tempPacket.size step BLE_TRANSFER_SIZE)
        {
            val endIndex = if(tempPacket.size > startIndex + BLE_TRANSFER_SIZE) startIndex + BLE_TRANSFER_SIZE else tempPacket.size
            writePacket.add(ArrayList(tempPacket.subList(startIndex, endIndex)))
        }
        
        tempPacket.clear()
    }
    
    
    private suspend fun waitNotification(waitingTime: Long): Boolean
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
                builder.append("\n")
            }
            builder.append(String.format("%02X", this[i]))
            builder.append("\t")
        }
        return builder.toString()
    }
    
    private fun buildTestPacket(payload: ByteArray): ByteArray
    {
        var length = 0
        for(i in 0 until 2)
        {
            length = length shl 8 or (payload[i].toInt() and 0xFF)
        }
        log("MCU Write is executing. Input bytes: $length")
        val paddingSize = length - payload.size - 2
        if(paddingSize <= 0) return byteArrayOf(*payload, 0xD0.toByte(), 0xD1.toByte())
        else return byteArrayOf(*payload, 0xD0.toByte(), 0xD1.toByte(),
            *ByteArray(paddingSize) { i -> (i % 256).toByte() })
    }
    
    fun initActivity(activity: AppCompatActivity)
    {
        this.activity = activity
    }
    
    fun setReadLength(length: Int)
    {
        readLength = length
    }
    
    fun setOpcodeLength(length: Int)
    {
        opcodeLength = length
    }
    
    fun getOpcodeLength(): Int
    {
        return opcodeLength
    }
    
    fun setNotificationTrigger(value: Boolean)
    {
        receivedNotification = value
    }
    
    fun cacheCharacteristicValue(payload: ArrayList<Byte>)
    {
        characteristicValue = payload
    }
    
    fun getErrorCode(): Int
    {
        return errorCode
    }
}