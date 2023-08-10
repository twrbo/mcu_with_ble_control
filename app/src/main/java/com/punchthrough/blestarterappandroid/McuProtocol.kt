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
import com.google.firestore.v1.Write
import com.punchthrough.blestarterappandroid.ble.CharacteristicRead
import com.punchthrough.blestarterappandroid.ble.CharacteristicWrite
import com.punchthrough.blestarterappandroid.ble.ConnectionEventListener
import com.punchthrough.blestarterappandroid.ble.ConnectionManager
import com.punchthrough.blestarterappandroid.ble.ConnectionManager.isConnected
import com.punchthrough.blestarterappandroid.ble.DescriptorRead
import com.punchthrough.blestarterappandroid.ble.EnableNotifications
import com.punchthrough.blestarterappandroid.ble.isWritable
import com.punchthrough.blestarterappandroid.ble.isWritableWithoutResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import java.lang.Integer.min
import kotlin.experimental.and
import kotlin.experimental.or
import kotlin.math.log

object McuProtocol
{
    private const val MAX_PACKET_SIZE: Int = 64             // USB2.0 full speed max packet size
    private const val deviceRxBufferSize: Int = 64        // device rx buffer size
    private const val deviceTxBufferSize: Int = 64        // device tx buffer size
    private const val deviceDataBufferSize: Int = 512     // device data buffer size
    
    // Control byte definition //
    private const val MCU_START: Byte = 0x01                // Control byte - Start
    private const val MCU_STOP: Byte = 0x02                 // Control byte - Stop
    private const val MCU_READ: Byte = 0x04                 // Control byte - Read
    private const val MCU_WRITE: Byte = 0x00                // Control byte - Write
    private const val MCU_UPDATE: Byte = 0x08               // Control byte - Update
    private const val MCU_CHECK: Byte = 0x10                // Control byte - Check
    
    // Error code definition //
    // MCU state
    private const val MCU_STATE_OK: Byte = 0x0000          // MCU OK
    private const val MCU_STATE_BUSY: Byte = 0x0001        // MCU busy
    private const val MCU_STATE_CMD_ERROR: Byte = 0x0010   // MCU CMD Error
    
    
    private var dataLength = 0

//    var readBuffer: ArrayList<Byte> = ArrayList<Byte>(List(1) { 0 })
    
    var readBuffer: ArrayList<Byte> = ArrayList()
    var isTrigger: Boolean = false
    
    
    fun write(device: BluetoothDevice, characteristic: BluetoothGattCharacteristic, payload: ByteArray, context: Context)
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
                return
            }
        }
        if(device.isConnected())
        {

// Send any length of data
//            var length: Int = 0
//            for(element in payload)
//            {
//                length = length shl 8 or (element.toInt() and 0xFF)
//            }
//            val data = ByteArray(length) { i -> (i).toByte() }
            
            
            CoroutineScope(Dispatchers.Main).launch {
                ConnectionManager.writeCharacteristic(device, characteristic, payload, context)
            }
            
            
        }
        else
        {
            Timber.e("Not connected to ${device.address}, cannot perform characteristic write")
        }
    }
    
    fun read(device: BluetoothDevice, characteristic: BluetoothGattCharacteristic, payload: ByteArray, context: Context)
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
                return
            }
        }
        if(device.isConnected())
        {
            
            Timber.e("DataLength: $dataLength")
            CoroutineScope(Dispatchers.Main).launch {
                ConnectionManager.writeCharacteristic(device, characteristic, payload, context)
                awaitEvent()
                readBuffer[0] = MCU_STATE_OK
                if(readBuffer[0] == MCU_STATE_OK)
                {
                    awaitEvent()
                    for(i in readBuffer) Timber.e("Data:$i")
//                    val cmd = ByteArray(5) { i -> (i).toByte() }
//                    ConnectionManager.writeCharacteristic(device, characteristic, cmd, context)
                }
                
            }
        }
        else
        {
            Timber.e("Not connected to ${device.address}, cannot perform characteristic write")
        }
    }
    
    fun setDataLength(length: Int)
    {
        dataLength = length
    }
    
    private fun checkDeviceState(): Boolean
    {
        val inputData = ByteArray(64) { 0xFF.toByte() }
        val outputData = ByteArray(64) { 0xFF.toByte() }
        inputData[0] = MCU_CHECK.toByte()
//
//        if (!writeBuffer(inputData)) {
//            println("[Error] [Check State] Write buffer failed !")
//            return false
//        }
//
//        if (!readBuffer(outputData)) {
//            println("[Error] [Check State] Read buffer failed !")
//            return false
//        }
//
//        if (outputData[0] != MCU_STATE_OK.toByte()) {
//            errorCode = outputData[0].toInt() // Update MCU error code
//            return false
//        }
//
        return true
    }
    
    private fun checkDeviceStateEx(): Boolean
    {
        var checkCurrentTimes = 0
//
//        while (checkCurrentTimes < maxCheckTimes) {
//            if (checkDeviceState()) {
//                return true
//            } else if (errorCode == ERROR_USB_TIMEOUT) {
//                println("[Warning] [Check State] Time out, times : ${++checkCurrentTimes}")
//                // Reconnect
//                if (autoReconnect && checkCurrentTimes < maxCheckTimes && !reconnect()) {
//                    println("[Error] [Check State] Reconnect failed !")
//                }
//            } else if (errorCode == MCU_STATE_BUSY) {
//                println("[Warning] [Check State] MCU busy, times : ${++checkCurrentTimes}")
//                // Reconnect
//                if (autoReconnect && checkCurrentTimes < maxCheckTimes && !reconnect()) {
//                    println("[Error] [Check State] Reconnect failed !")
//                }
//            } else {
//                println("[Error] [Check State] Unexpected error !")
//                return false
//            }
//
//            delay(checkIntervalTime) // check state interval time
//        }
//
        println("[Error] [Check State] Max retries exceeded !")
        return false
    }
    
    private fun createWritePackage(writeData: ByteArray): List<ByteArray>
    {
        val packetSize = 64 // packet size
        val dataBufferSize = 512 // device data buffer size
        
        val packedData = mutableListOf<ByteArray>()
        var dataIdx = 0
        
        while(dataIdx < writeData.size)
        {
            val remainingData = writeData.size - dataIdx
            val packetDataLen = min(packetSize - 1, remainingData)
            
            val packet = ByteArray(packetSize) { 0xFF.toByte() }
            packet[0] = MCU_WRITE.toByte()
            
            if(dataIdx % dataBufferSize == 0)
            {
                packet[0] = ((packet[0].toInt() or MCU_START.toInt()).toByte())
            }
            
            if(remainingData <= dataBufferSize - dataIdx % dataBufferSize)
            {
                packet[0] = ((packet[0].toInt() or MCU_UPDATE.toInt()).toByte())
            }
            
            writeData.copyInto(packet, 1, dataIdx, dataIdx + packetDataLen)
            
            dataIdx += packetDataLen
            packedData.add(packet)
        }
        
        if(packedData.isNotEmpty())
        {
            packedData.last()[0] = ((packedData.last()[0].toInt() or MCU_STOP.toInt()).toByte())
        }
        
        return packedData
    }
    
    private suspend fun awaitEvent()
    {
        // 等待特定事件，這裡假設你要等待 isTrigger 變為 true
        while(!isTrigger)
        {
            delay(100) // 延遲 100 毫秒，然後再次檢查條件
        }
        isTrigger = false;
        // 進行後續操作
        Timber.e("isTrigger 已經為 true，可以繼續執行後續操作")
    }
    
}