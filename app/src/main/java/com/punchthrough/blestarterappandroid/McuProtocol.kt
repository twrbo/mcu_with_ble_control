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
import android.content.Context
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
import kotlin.experimental.or

object McuProtocol
{
    
    private const val MCU_BUFFER_SIZE: Int = 1024           // device data buffer size
    private const val BLE_TRANSFER_SIZE: Int = 512
    
    // Control Byte //
    private const val MCU_READ: Byte = 0x04
    private const val MCU_WRITE: Byte = 0x00
    private const val MCU_CHECK: Byte = 0x10
    private const val MCU_CMD: Byte = 0x01
    
    // Error Code //
    // MCU state
    const val MCU_STATE_OK: Byte = 0x0000          // MCU OK
    private const val MCU_STATE_BUSY: Byte = 0x0001        // MCU busy
    private const val MCU_STATE_CMD_ERROR: Byte = 0x0010   // MCU CMD Error
    
    
    // Parameter
    private var receivedBuffer: ArrayList<Byte> = ArrayList()
    private var isTrigger: Boolean = false
    private var requestedDataLength = 0
    private var errorCode: Byte = MCU_STATE_OK
    
    private var intervalTime: Long = 5       // Interval time for readBuffer, writeBuffer function
    private var waitRWTime: Long = 10        // Wait time for device read/write data to target device
    private var checkIntervalTime: Long = 50 // The interval time of check device
    private var maxCheckTimes: Int = 3      // Max times of check device state
    
    private lateinit var activity: AppCompatActivity
    
    // 初始化方法，用於設置 BleOperationsActivity 的參考
    fun init(activity: AppCompatActivity)
    {
        this.activity = activity
    }
    
    private fun log(message: String)
    {
        val dateFormatter = SimpleDateFormat("MMM d, HH:mm:ss", Locale.US)
        val formattedMessage = String.format("%s: %s", dateFormatter.format(Date()), message)
        val textViewLog = activity.findViewById<TextView>(R.id.log_text_view)
        val scrollViewLog = activity.findViewById<ScrollView>(R.id.log_scroll_view)
        activity.runOnUiThread {
            
            val currentLogText = textViewLog.text.ifEmpty {
                "Beginning of log."
            }
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
    
    fun setReceivedBuffer(payload: ArrayList<Byte>)
    {
        receivedBuffer = payload
    }
    
    fun getReceivedBuffer(): ArrayList<Byte>
    {
        return receivedBuffer
    }
    
    fun getErrorCode(): Byte
    {
        return errorCode
    }
    
    suspend fun write(device: BluetoothDevice, characteristic: BluetoothGattCharacteristic, context: Context, payload: ByteArray): Boolean
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
            val writePacket = buildWritePacket(payload)
            var packetIndex = 0
            val packetsPerTransfer = 3
            var retryTimes = 0
            
            
            // Check status
            if(!checkMcuStateEx(device, characteristic, context))
            {
                log("[Error] [Write Data] check device state error (first) !")
                return false
            }
            
            log("CheckStatus success!")
//            while(packetIndex < writePacket.size)
//            {
//                delay(intervalTime)
//                ConnectionManager.writeCharacteristic(device, characteristic, context, writePacket[packetIndex++].toByteArray())
//
//                if(packetIndex % packetsPerTransfer == 0 && retryTimes < maxCheckTimes)
//                {
//                    // if CRC is incorrect
//                    if(!isMcuStateOk())
//                    {
//                        packetIndex -= packetsPerTransfer
//                        retryTimes++
//                    }
//                    else retryTimes = 0
//                }
//                else if(packetIndex == writePacket.size && retryTimes < maxCheckTimes)
//                {
//                    // if CRC is incorrect
//                    if(!isMcuStateOk())
//                    {
//                        packetIndex -= (packetIndex % packetsPerTransfer)
//                        retryTimes++
//                    }
//                    else retryTimes = 0
//                }
//            }
            
            return true
        }
        else
        {
            Timber.e("Not connected to ${device.address}, cannot perform characteristic write")
        }
        return false
    }
    
    suspend fun read(device: BluetoothDevice, characteristic: BluetoothGattCharacteristic, context: Context, payload: ByteArray): Boolean
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
            val readCMD = ByteArray(64) { 0xFF.toByte() }
            readCMD[0] = MCU_READ
            val readData = ArrayList<Byte>()
            var retryTimes = 0
            
            // Check status
            if(!checkMcuStateEx(device, characteristic, context))
            {
                println("[Error] [Write Data] check device state error (first) !")
                return false
            }
            
            // Write READ INFO
            ConnectionManager.writeCharacteristic(device, characteristic, context, payload)
            if(!isMcuStateOk())
            {
                return false
            }
            
            // Check status
            if(!checkMcuStateEx(device, characteristic, context))
            {
                println("[Error] [Write Data] check device state error (first) !")
                return false
            }
            
            // Write READ CMD
            while(readData.size < requestedDataLength && retryTimes < 3)
            {
                ConnectionManager.writeCharacteristic(device, characteristic, context, readCMD)
                if(getReadData())
                {
                    readData.addAll(getReceivedBuffer())
                    retryTimes = 0
                }
                else retryTimes++
            }
            
        }
        else
        {
            Timber.e("Not connected to ${device.address}, cannot perform characteristic write")
            return false
        }
        
        return true
    }
    
    private suspend fun checkMcuState(device: BluetoothDevice, characteristic: BluetoothGattCharacteristic, context: Context): Boolean
    {
        val checkPacket = ByteArray(64) { 0xFF.toByte() }
        checkPacket[0] = MCU_CHECK
        
        if(!ConnectionManager.writeCharacteristic(device, characteristic, context, checkPacket)) return false
        
        if(!isMcuStateOk()) return false
        
        return true
    }
    
    private suspend fun isMcuStateOk(): Boolean
    {
        waitCharacteristicChanged()
        if(receivedBuffer[0] != MCU_STATE_OK)
        {
            errorCode = receivedBuffer[0]
            return false
        }
        return true
    }
    
    private suspend fun getReadData(): Boolean
    {
        waitCharacteristicChanged()
        TODO("CHECK CRC")
        return true
    }
    
    
    private suspend fun checkMcuStateEx(device: BluetoothDevice, characteristic: BluetoothGattCharacteristic, context: Context): Boolean
    {
        var currentTimes = 0
        
        while(currentTimes < maxCheckTimes)
        {
            if(checkMcuState(device, characteristic, context))
            {
                return true
            }
            else if(errorCode == MCU_STATE_BUSY)
            {
                println("[Warning] [Check State] MCU busy, times : ${++currentTimes}")
            }
            else
            {
                println("[Error] [Check State] Unexpected error !")
                return false
            }
            
            delay(checkIntervalTime) // check state interval time
        }
        
        println("[Error] [Check State] Max retries exceeded !")
        return false
    }
    
    private fun buildWritePacket(sourceData: ByteArray): ArrayList<ArrayList<Byte>>
    {
        val writePacket: ArrayList<ArrayList<Byte>> = ArrayList()
        var packet = ArrayList<Byte>(List(BLE_TRANSFER_SIZE) { 0xFF.toByte() })
        var dataInPacket = 0
        var chkSum = 0
        
        // first packet
        packet[dataInPacket++] = MCU_WRITE or MCU_CMD
        
        for((sourceDataIndex, byteValue) in sourceData.withIndex())
        {
            packet[dataInPacket++] = byteValue
            
            if(dataInPacket % BLE_TRANSFER_SIZE == 0)
            {
                writePacket.add(packet)
                
                // Initialize the packet
                if(sourceDataIndex < sourceData.size)
                {
                    packet = ArrayList<Byte>(List(BLE_TRANSFER_SIZE) { 0xFF.toByte() })
                    dataInPacket = 0
                    packet[dataInPacket++] = MCU_WRITE
                }
            }
        }
        
        return writePacket
    }
    
    
    private suspend fun waitCharacteristicChanged()
    {
        while(!isTrigger)
        {
            delay(10)
        }
        isTrigger = false;
    }
    
    private fun buildNbytesPacket(payload: ByteArray): ByteArray
    {
        var length: Int = 0
        for(element in payload)
        {
            length = length shl 8 or (element.toInt() and 0xFF)
        }
        
        return ByteArray(length) { i -> i.toByte() }
    }
    
    
}