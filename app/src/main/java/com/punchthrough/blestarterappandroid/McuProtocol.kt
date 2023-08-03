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
import com.punchthrough.blestarterappandroid.ble.ConnectionManager
import com.punchthrough.blestarterappandroid.ble.ConnectionManager.isConnected
import com.punchthrough.blestarterappandroid.ble.isWritable
import com.punchthrough.blestarterappandroid.ble.isWritableWithoutResponse
import timber.log.Timber

object McuProtocol
{
    private const val MAX_PACKET_SIZE: Int = 64             // USB2.0 full speed max packet size
    private const val deviceRxBufferSize: Short = 64        // device rx buffer size
    private const val deviceTxBufferSize: Short = 64        // device tx buffer size
    private const val deviceDataBufferSize: Short = 512     // device data buffer size
    
    // Control byte definition //
    private const val MCU_START: Byte = 0x01                // Control byte - Start
    private const val MCU_STOP: Byte = 0x02                 // Control byte - Stop
    private const val MCU_READ: Byte = 0x04                 // Control byte - Read
    private const val MCU_WRITE: Byte = 0x00                // Control byte - Write
    private const val MCU_UPDATE: Byte = 0x08               // Control byte - Update
    private const val MCU_CHECK: Byte = 0x10                // Control byte - Check
    
    // Error code definition //
    // MCU state
    private const val MCU_STATE_OK: Short = 0x0000          // MCU OK
    private const val MCU_STATE_BUSY: Short = 0x0001        // MCU busy
    private const val MCU_STATE_CMD_ERROR: Short = 0x0010   // MCU CMD Error
    
    // BLE State
    private const val BLE_PendingUpdate = 0x1000
    
    
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
            //            var modifiedPayload = byteArrayOf(0x11) + payload
            
            val length: Int = ((payload[0].toInt() shr 4) and 0xF) * 16 * 16 + (payload[0].toInt() and 0x0F) * 16 + (payload[1].toInt()) * 1
            Timber.e(length.toString())
            val data = ByteArray(length) { i -> (i).toByte() }
            ConnectionManager.enqueueOperation(CharacteristicWrite(device, characteristic.uuid, writeType, data), context)
            ConnectionManager.enqueueOperation(CharacteristicRead(device, characteristic.uuid), context)
            
            // wait 1 second
//            CoroutineScope(Dispatchers.Main).launch {
//                delay(1000)
//                modifiedPayload = byteArrayOf(0x22) + payload
//                enqueueOperation(CharacteristicWrite(device, characteristic.uuid, writeType, modifiedPayload))
//            }
        }
        else
        {
            Timber.e("Not connected to ${device.address}, cannot perform characteristic write")
        }
    }
    
    fun read(device: BluetoothDevice, characteristic: BluetoothGattCharacteristic, payload: ByteArray)
    {
    
    }
    
    private fun checkStatus()
    {
    
    }
    
    private fun checkStatusEx()
    {
    
    }
    
    private fun createWritePacket()
    {
    
    }
}