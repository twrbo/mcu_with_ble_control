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
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.CRC32

object McuProtocol
{
    // Size
    private const val MCU_BUFFER_SIZE: Int = 1024   // device data buffer size
    private const val MCU_PACKET_SIZE: Int = 64
    private const val BLE_TRANSFER_SIZE: Int = 512
    private const val CRC_SIZE: Int = 4
    private const val WRITE_CMD_SIZE: Int = 7
    
    // MCU Protocol Defined ------------------------------ //
    // Control Byte
    private const val MCU_READ: Byte = 0x04
    private const val MCU_WRITE: Byte = 0x00
    private const val MCU_CHECK: Byte = 0x10
    
    // MCU State
    const val MCU_STATE_OK: Byte = 0x00
    private const val MCU_STATE_BUSY: Byte = 0x01
    private const val MCU_STATE_CMD_ERROR: Byte = 0x10
    // MCU Protocol Defined ------------------------------ //
    
    private const val BLE_TIME_OUT: Byte = 0x0A
    private const val CRC_ERROR: Byte = 0x0B
    private const val MCU_PACKET_SIZE_UNMATCHED: Byte = 0x0C
    
    // Parameter ------------------------------------------------------------- //
    private var receivedBuffer: ArrayList<Byte> = ArrayList<Byte>()
    private var isTrigger: Boolean = false
    private var requestedDataLength: Int = 0
    private var errorCode: Byte = MCU_STATE_OK
    
    private var intervalTime: Long = 5       // Interval time for readBuffer, writeBuffer function
    private var waitRWTime: Long = 10        // Wait time for device read/write data to target device
    private var checkIntervalTime: Long = 50 // The interval time of check device
    private var maxCheckTimes: Int = 3      // Max times of check device state
    private var waitingNotificationTime: Long = 10000
    private var intervalWriteTime: Long = 1
    
    private lateinit var activity: AppCompatActivity
    // Parameter ------------------------------------------------------------- //
    
    suspend fun write(device: BluetoothDevice, characteristic: BluetoothGattCharacteristic, payload: ByteArray): Boolean
    {
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
            // Test Data
            val payload = buildNBytesPacket(payload)
            
            val writePacket = buildWritePackets(payload)
            var packetIndex = 0
            val packetsPerTransfer = 3
            var retryTimes = 0
            var remainingByte = payload.size
            // Test CRC
//            val testData = ArrayList<Byte>()
//            for(i in 0 until 509)
//            {
//                testData.add((i % 256).toByte())
//            }
//            val crcTestBytes = generateCRC32(testData)
//            Timber.e("crcTestData: ${testData.toHexString()}")
//            Timber.e("crcTestValue: ${crcTestBytes.toHexString()}")
            
            
            // Check status
            if(!checkMcuStateEx(device, characteristic))
            {
                return false
            }
            
            log("MCU Write is executing. Bytes to be written: ${payload.size}")
            while(packetIndex < writePacket.size)
            {
                delay(intervalTime)

//                log("WritePacket[${packetIndex+1}]${writePacket[packetIndex].toHexString()}")
                log("Remaining bytes: $remainingByte")
                ConnectionManager.writeCharacteristic(device, characteristic, writePacket[packetIndex++].toByteArray())
                remainingByte -= writePacket[packetIndex - 1].size
                
                if(packetIndex % packetsPerTransfer == 0 && retryTimes < maxCheckTimes)
                {
                    remainingByte += 57
                    if(!isMcuStateOk())
                    {
                        // Re-transfer
                        packetIndex -= packetsPerTransfer
                        retryTimes++
                    }
                    else retryTimes = 0
                }
                else if(packetIndex == writePacket.size && retryTimes < maxCheckTimes)
                {
                    remainingByte += 5
                    if(!isMcuStateOk())
                    {
                        // Re-transfer
                        packetIndex -= (packetIndex % packetsPerTransfer)
                        retryTimes++
                    }
                    else retryTimes = 0
                }
                
                
                if(errorCode == MCU_STATE_BUSY)
                {
                    log("[Warning] [Write] MCU busy, times : $retryTimes")
                }
                else if(errorCode == BLE_TIME_OUT)
                {
                    log("[Warning] [Write] Time out, no response, times : $retryTimes")
                }
                else if(errorCode == CRC_ERROR)
                {
                    log("[Warning] [Write] CRC32 check error")
                }
                else if(errorCode == MCU_PACKET_SIZE_UNMATCHED)
                {
                    log("[Error] [Write] MCU packet size is less than 64")
                    return false
                }
                else if(errorCode != MCU_STATE_OK)
                {
                    log("[Error] [Write] Unexpected error ! Error code: ${String.format("%02X", errorCode)}")
                    return false
                }
            }
            
            log("MCU Write is done.")
            log("total packet: ${writePacket.size}")
            return true
        }
        else
        {
            Timber.e("Not connected to ${device.address}, cannot perform characteristic write")
        }
        return false
    }
    
    suspend fun read(device: BluetoothDevice, characteristic: BluetoothGattCharacteristic, payload: ByteArray): Boolean
    {
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
            
            
            // Check status
            if(!checkMcuStateEx(device, characteristic))
            {
                return false
            }
            // Make log order correct
            delay(intervalWriteTime)
            
            // Build ReadInfo packet
            val readINFO = ArrayList<Byte>(List(MCU_PACKET_SIZE - CRC_SIZE - 1) { 0xFF.toByte() })   // Control Byte: 1 byte
            for(i in payload.indices)
            {
                readINFO[i] = payload[i]
            }
            var crcBytes = generateCRC32(readINFO)
            readINFO.addAll(0, crcBytes)
            readINFO.add(0, MCU_READ)
            // Send ReadInfo packet
            ConnectionManager.writeCharacteristic(device, characteristic, readINFO.toByteArray())
            
            // DEBUG
//            log("SentReadInfo, ${readINFO.toHexString()}")
            // Make log order correct
            delay(intervalWriteTime)
            
            if(!isMcuStateOk())
            {
                return false
            }
            
            // Make log order correct
            delay(intervalWriteTime)
            
            
            // Build ReadCMD
            val readCMD = ArrayList<Byte>(List(MCU_PACKET_SIZE - CRC_SIZE - 1) { 0xFF.toByte() })   // Control Byte: 1 byte
            crcBytes = generateCRC32(readCMD)
            readCMD.addAll(0, crcBytes)
            readCMD.add(0, MCU_READ)
            log("MCU Read is executing. Request length: $requestedDataLength")
            
            val data = ArrayList<Byte>()
            lateinit var tempBuffer: ArrayList<Byte>
            var remainingDataLength: Int = 0
            var retryTimes = 0
            var dummySize = 0
            var maxDataLength: Int = 0
            while(data.size < requestedDataLength && retryTimes < 1)
            {
                // Check status
                if(!checkMcuStateEx(device, characteristic))
                {
                    return false
                }
                // Send ReadCMD packet
                ConnectionManager.writeCharacteristic(device, characteristic, readCMD.toByteArray())
                
                
                // Make log order correct
                delay(intervalWriteTime)
                
                
                remainingDataLength = requestedDataLength - data.size
                // DEBUG
                log("Remaining data length: $remainingDataLength.")
                tempBuffer = ArrayList<Byte>()
                if(remainingDataLength > MCU_BUFFER_SIZE - CRC_SIZE)
                {
                    // Get via 3 notifications
                    if(!getReceivedData(tempBuffer, 3))
                    {
                        return false
                    }
                    maxDataLength = if(remainingDataLength > MCU_BUFFER_SIZE) MCU_BUFFER_SIZE else remainingDataLength
                }
                else if(((BLE_TRANSFER_SIZE - CRC_SIZE) < remainingDataLength) && (remainingDataLength <= (MCU_BUFFER_SIZE - CRC_SIZE)))
                {
                    // Get via 2 notifications
                    if(!getReceivedData(tempBuffer, 2))
                    {
                        return false
                    }
                    maxDataLength = remainingDataLength
                }
                else
                {
                    // Get via 1 notification
                    if(!getReceivedData(tempBuffer, 1))
                    {
                        return false
                    }
                    maxDataLength = remainingDataLength
                }
                
                // Check CRC32
                if(verifyCRC32(tempBuffer))
                {
                    if((maxDataLength + CRC_SIZE) % MCU_PACKET_SIZE != 0) dummySize = MCU_PACKET_SIZE - ((maxDataLength + CRC_SIZE) % MCU_PACKET_SIZE)
                    else dummySize = 0
                    // Remove dummy and CRC bytes
                    data.addAll(tempBuffer.subList(CRC_SIZE, tempBuffer.size - dummySize))
                    retryTimes = 0
                }
                else
                {
                    log("[Warning] [Read] CRC32 check error")
                    retryTimes++
                }
            }
            
            
            if(retryTimes >= maxCheckTimes)
            {
                log("[Error] [Read] Max retries exceeded !")
                return false
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
    
    private suspend fun checkMcuStateEx(device: BluetoothDevice, characteristic: BluetoothGattCharacteristic): Boolean
    {
        var currentTimes = 0
        
        while(currentTimes < maxCheckTimes)
        {
            if(checkMcuState(device, characteristic))
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
            else if(errorCode == CRC_ERROR)
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
    
    private suspend fun checkMcuState(device: BluetoothDevice, characteristic: BluetoothGattCharacteristic): Boolean
    {
        val checkPacket = ArrayList<Byte>(List(MCU_PACKET_SIZE - CRC_SIZE - 1) { 0xFF.toByte() })   // Control Byte: 1 byte
        val crcBytes = generateCRC32(checkPacket)
        
        // Put CRC32 Bytes after Control Byte
        checkPacket.addAll(0, crcBytes)
        checkPacket.add(0, MCU_CHECK)
        
        // DEBUG
//        log("SentCheckPacket, ${checkPacket.toHexString()}")
        
        if(!ConnectionManager.writeCharacteristic(device, characteristic, checkPacket.toByteArray())) return false
        
        if(!isMcuStateOk()) return false
        
        return true
    }
    
    private suspend fun isMcuStateOk(): Boolean
    {
        if(waitCharacteristicChanged(waitingNotificationTime))
        {
            // DEBUG
//            log("ReceivedStatusPacket, ${receivedBuffer.toHexString()}")
            if(receivedBuffer.size < MCU_PACKET_SIZE)
            {
                errorCode = MCU_PACKET_SIZE_UNMATCHED
                return false
            }
            
            if(!verifyCRC32(receivedBuffer))
            {
                errorCode = CRC_ERROR
                return false
            }
            
            if(receivedBuffer[CRC_SIZE] != MCU_STATE_OK)
            {
                errorCode = receivedBuffer[CRC_SIZE]
                return false
            }
            else
            {
                errorCode = MCU_STATE_OK
                return true
            }
        }
        else
        {
            errorCode = BLE_TIME_OUT
            return false
        }
    }
    
    private fun buildWritePackets(sourceData: ByteArray): ArrayList<ArrayList<Byte>>
    {
        val writePacket: ArrayList<ArrayList<Byte>> = ArrayList()
//        var packet = ArrayList<Byte>(List(BLE_TRANSFER_SIZE) { 0xFF.toByte() })
        var packet = ArrayList<Byte>()
        var tempPacket = ArrayList<Byte>()
        var crcBytes = ArrayList<Byte>()
        var isFirstPacket = true
        var dummySize = 0
        
        
        for((sourceDataIndex, byteData) in sourceData.withIndex())
        {
            tempPacket.add(byteData)
            // 1st. write operation
            if(tempPacket.size == MCU_BUFFER_SIZE + WRITE_CMD_SIZE && isFirstPacket)
            {
                isFirstPacket = false
                dummySize = MCU_PACKET_SIZE - WRITE_CMD_SIZE - CRC_SIZE - 1
                // Generate CRC32 value
                for(i in 0 until dummySize)
                {
                    tempPacket.add(0xFF.toByte())
                }
                crcBytes = generateCRC32(tempPacket)
                tempPacket.addAll(0, crcBytes)
                tempPacket.add(0, MCU_WRITE)
                
                // Assign to writePacket
                writePacket.add(ArrayList(tempPacket.subList(0, BLE_TRANSFER_SIZE)))
                writePacket.add(ArrayList(tempPacket.subList(BLE_TRANSFER_SIZE, BLE_TRANSFER_SIZE * 2)))
                writePacket.add(ArrayList(tempPacket.subList(BLE_TRANSFER_SIZE * 2, BLE_TRANSFER_SIZE * 2 + MCU_PACKET_SIZE)))
                tempPacket.clear()
            }
            // 2nd. ~ N-1 th. write operation
            else if(tempPacket.size == MCU_BUFFER_SIZE && !isFirstPacket)
            {
                dummySize = MCU_PACKET_SIZE - CRC_SIZE - 1
                // Generate CRC32 value
                for(i in 0 until dummySize)
                {
                    tempPacket.add(0xFF.toByte())
                }
                crcBytes = generateCRC32(tempPacket)
                tempPacket.addAll(0, crcBytes)
                tempPacket.add(0, MCU_WRITE)
                
                // Assign to writePacket
                writePacket.add(ArrayList(tempPacket.subList(0, BLE_TRANSFER_SIZE)))
                writePacket.add(ArrayList(tempPacket.subList(BLE_TRANSFER_SIZE, BLE_TRANSFER_SIZE * 2)))
                writePacket.add(ArrayList(tempPacket.subList(BLE_TRANSFER_SIZE * 2, BLE_TRANSFER_SIZE * 2 + MCU_PACKET_SIZE)))
                tempPacket.clear()
            }
            // N write operation
            else if(sourceDataIndex == sourceData.size - 1)
            {
                if(tempPacket.size % MCU_PACKET_SIZE != 0) dummySize = MCU_PACKET_SIZE - (tempPacket.size % MCU_PACKET_SIZE) - CRC_SIZE - 1
                else dummySize = 0
                // Generate CRC32 value
                for(i in 0 until dummySize)
                {
                    tempPacket.add(0xFF.toByte())
                }
                crcBytes = generateCRC32(tempPacket)
                tempPacket.addAll(0, crcBytes)
                tempPacket.add(0, MCU_WRITE)
                
                // Assign to writePacket
                if(tempPacket.size <= BLE_TRANSFER_SIZE)
                {
                    writePacket.add(ArrayList(tempPacket.subList(0, tempPacket.size)))
                }
                else if(tempPacket.size > BLE_TRANSFER_SIZE && tempPacket.size <= BLE_TRANSFER_SIZE * 2 - CRC_SIZE - 1)
                {
                    writePacket.add(ArrayList(tempPacket.subList(0, BLE_TRANSFER_SIZE)))
                    writePacket.add(ArrayList(tempPacket.subList(BLE_TRANSFER_SIZE, tempPacket.size)))
                }
                else if(tempPacket.size > BLE_TRANSFER_SIZE * 2 - CRC_SIZE - 1)
                {
                    writePacket.add(ArrayList(tempPacket.subList(0, BLE_TRANSFER_SIZE)))
                    writePacket.add(ArrayList(tempPacket.subList(BLE_TRANSFER_SIZE, BLE_TRANSFER_SIZE * 2)))
                    writePacket.add(ArrayList(tempPacket.subList(BLE_TRANSFER_SIZE * 2, tempPacket.size)))
                }
                tempPacket.clear()
            }
            
        }


//        // first packet
//        packet[dataInPacket++] = MCU_WRITE
//
//        for((sourceDataIndex, byteValue) in sourceData.withIndex())
//        {
//            packet[dataInPacket++] = byteValue
//
//            if(dataInPacket % BLE_TRANSFER_SIZE == 0)
//            {
//                writePacket.add(packet)
//
//                // Initialize the packet
//                if(sourceDataIndex < sourceData.size)
//                {
//                    packet = ArrayList<Byte>(List(BLE_TRANSFER_SIZE) { 0xFF.toByte() })
//                    dataInPacket = 0
//                    packet[dataInPacket++] = MCU_WRITE
//                }
//            }
//        }
        
        return writePacket
    }
    
    private fun getReceivedData(): ArrayList<Byte>
    {
        return receivedBuffer
    }
    
    private suspend fun getReceivedData(tempBuffer: ArrayList<Byte>, times: Int): Boolean
    {
        for(i in 0 until times)
        {
            if(waitCharacteristicChanged(waitingNotificationTime)) tempBuffer.addAll(receivedBuffer)
            else
            {
                errorCode = BLE_TIME_OUT
                log("[Error] [Read] Time out at no.${i + 1} transaction.")
                return false
            }
        }
        return true
    }
    
    private suspend fun waitCharacteristicChanged(waitingTime: Long): Boolean
    {
        val startTime = System.currentTimeMillis()
        
        while(!isTrigger)
        {
            if(System.currentTimeMillis() - startTime > waitingTime)
            {
                break
            }
        }
        
        if(isTrigger)
        {
            isTrigger = false
            return true
        }
        else return false
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
                errorCode = CRC_ERROR
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
        val textViewLog = activity.findViewById<TextView>(R.id.log_text_view)
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
    
    fun setTrigger()
    {
        isTrigger = true;
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
    
    fun getErrorCode(): Byte
    {
        return errorCode
    }
    
    private fun buildNBytesPacket(payload: ByteArray): ByteArray
    {
        var length: Int = 0
        for(element in payload)
        {
            length = length shl 8 or (element.toInt() and 0xFF)
        }
        
        return ByteArray(length) { i -> (i % 256).toByte() }
    }
    
}