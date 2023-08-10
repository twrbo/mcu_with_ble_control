/*
 * Copyright 2019 Punch Through Design LLC
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

package com.punchthrough.blestarterappandroid.ble

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import timber.log.Timber
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import android.app.Activity
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import com.punchthrough.blestarterappandroid.BLUETOOTH_CONNECT_PERMISSION_REQUEST_CODE
import com.punchthrough.blestarterappandroid.BLUETOOTH_SCAN_PERMISSION_REQUEST_CODE
import com.punchthrough.blestarterappandroid.McuProtocol

private const val GATT_MIN_MTU_SIZE = 23

/** Maximum BLE MTU size as defined in gatt_api.h. */
private const val GATT_MAX_MTU_SIZE = 517

object ConnectionManager
{
    
    private var listeners: MutableSet<WeakReference<ConnectionEventListener>> = mutableSetOf()
    
    private val deviceGattMap = ConcurrentHashMap<BluetoothDevice, BluetoothGatt>()
    private val operationQueue = ConcurrentLinkedQueue<BleOperationType>()
    private var pendingOperation: BleOperationType? = null
    
    fun servicesOnDevice(device: BluetoothDevice): List<BluetoothGattService>? =
        deviceGattMap[device]?.services
    
    fun registerListener(listener: ConnectionEventListener)
    {
        if(listeners.map { it.get() }.contains(listener))
        {
            return
        }
        listeners.add(WeakReference(listener))
        listeners = listeners.filter { it.get() != null }.toMutableSet()
        Timber.d("Added listener $listener, ${listeners.size} listeners total")
    }
    
    fun unregisterListener(listener: ConnectionEventListener)
    {
        // Removing elements while in a loop results in a java.util.ConcurrentModificationException
        var toRemove: WeakReference<ConnectionEventListener>? = null
        listeners.forEach {
            if(it.get() == listener)
            {
                toRemove = it
            }
        }
        toRemove?.let {
            listeners.remove(it)
            Timber.d("Removed listener ${it.get()}, ${listeners.size} listeners total")
        }
    }
    
    fun connect(device: BluetoothDevice, context: Context)
    {
        if(device.isConnected())
        {
            Timber.e("Already connected to ${device.address}!")
        }
        else
        {
            enqueueOperation(Connect(device, context.applicationContext), context)
        }
    }
    
    fun teardownConnection(device: BluetoothDevice, context: Context)
    {
        if(device.isConnected())
        {
            enqueueOperation(Disconnect(device), context)
        }
        else
        {
            Timber.e("Not connected to ${device.address}, cannot teardown connection!")
        }
    }
    
    fun readCharacteristic(device: BluetoothDevice, characteristic: BluetoothGattCharacteristic, context: Context)
    {
        if(device.isConnected() && characteristic.isReadable())
        {
            enqueueOperation(CharacteristicRead(device, characteristic.uuid), context)
        }
        else if(!characteristic.isReadable())
        {
            Timber.e("Attempting to read ${characteristic.uuid} that isn't readable!")
        }
        else if(!device.isConnected())
        {
            Timber.e("Not connected to ${device.address}, cannot perform characteristic read")
        }
    }
    
    
    fun writeCharacteristic(device: BluetoothDevice, characteristic: BluetoothGattCharacteristic, payload: ByteArray, context: Context)
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
            enqueueOperation(CharacteristicWrite(device, characteristic.uuid, writeType, payload), context)
        }
        else
        {
            Timber.e("Not connected to ${device.address}, cannot perform characteristic write")
        }
    }
    
    fun readDescriptor(device: BluetoothDevice, descriptor: BluetoothGattDescriptor, context: Context)
    {
        if(device.isConnected() && descriptor.isReadable())
        {
            enqueueOperation(DescriptorRead(device, descriptor.uuid), context)
        }
        else if(!descriptor.isReadable())
        {
            Timber.e("Attempting to read ${descriptor.uuid} that isn't readable!")
        }
        else if(!device.isConnected())
        {
            Timber.e("Not connected to ${device.address}, cannot perform descriptor read")
        }
    }
    
    fun writeDescriptor(device: BluetoothDevice, descriptor: BluetoothGattDescriptor, payload: ByteArray, context: Context)
    {
        if(device.isConnected() && (descriptor.isWritable() || descriptor.isCccd()))
        {
            enqueueOperation(DescriptorWrite(device, descriptor.uuid, payload), context)
        }
        else if(!device.isConnected())
        {
            Timber.e("Not connected to ${device.address}, cannot perform descriptor write")
        }
        else if(!descriptor.isWritable() && !descriptor.isCccd())
        {
            Timber.e("Descriptor ${descriptor.uuid} cannot be written to")
        }
    }
    
    fun enableNotifications(device: BluetoothDevice, characteristic: BluetoothGattCharacteristic, context: Context)
    {
        if(device.isConnected() && (characteristic.isIndicatable() || characteristic.isNotifiable()))
        {
            enqueueOperation(EnableNotifications(device, characteristic.uuid), context)
        }
        else if(!device.isConnected())
        {
            Timber.e("Not connected to ${device.address}, cannot enable notifications")
        }
        else if(!characteristic.isIndicatable() && !characteristic.isNotifiable())
        {
            Timber.e("Characteristic ${characteristic.uuid} doesn't support notifications/indications")
        }
    }
    
    fun disableNotifications(device: BluetoothDevice, characteristic: BluetoothGattCharacteristic, context: Context)
    {
        if(device.isConnected() && (characteristic.isIndicatable() || characteristic.isNotifiable()))
        {
            enqueueOperation(DisableNotifications(device, characteristic.uuid), context)
        }
        else if(!device.isConnected())
        {
            Timber.e("Not connected to ${device.address}, cannot disable notifications")
        }
        else if(!characteristic.isIndicatable() && !characteristic.isNotifiable())
        {
            Timber.e("Characteristic ${characteristic.uuid} doesn't support notifications/indications")
        }
    }
    
    fun requestMtu(device: BluetoothDevice, mtu: Int, context: Context)
    {
        if(device.isConnected())
        {
            enqueueOperation(MtuRequest(device, mtu.coerceIn(GATT_MIN_MTU_SIZE, GATT_MAX_MTU_SIZE)), context)
        }
        else
        {
            Timber.e("Not connected to ${device.address}, cannot request MTU update!")
        }
    }
    
    // - Beginning of PRIVATE functions
    
    @Synchronized
    fun enqueueOperation(operation: BleOperationType, context: Context)
    {
        operationQueue.add(operation)
        if(pendingOperation == null)
        {
            doNextOperation(context)
        }
    }
    
    @Synchronized
    private fun signalEndOfOperation(context: Context)
    {
        Timber.d("End of $pendingOperation")
        pendingOperation = null
        if(operationQueue.isNotEmpty())
        {
            doNextOperation(context)
        }
    }
    
    /**
     * Perform a given [BleOperationType]. All permission checks are performed before an operation
     * can be enqueued by [enqueueOperation].
     */
    @Synchronized
    private fun doNextOperation(context: Context)
    {
        
        if(ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
        {
            ActivityCompat.requestPermissions(context as Activity, arrayOf(Manifest.permission.BLUETOOTH_SCAN), BLUETOOTH_SCAN_PERMISSION_REQUEST_CODE)
            return
        }
        
        if(pendingOperation != null)
        {
            Timber.e("doNextOperation() called when an operation is pending! Aborting.")
            return
        }
        
        val operation = operationQueue.poll() ?: run {
            Timber.v("Operation queue empty, returning")
            return
        }
        pendingOperation = operation
        
        // Handle Connect separately from other operations that require device to be connected
        if(operation is Connect)
        {
            with(operation) {
                Timber.w("Connecting to ${device.address}")
                device.connectGatt(context, false, callback)
            }
            return
        }
        
        // Check BluetoothGatt availability for other operations
        val gatt = deviceGattMap[operation.device] ?: this@ConnectionManager.run {
            Timber.e("Not connected to ${operation.device.address}! Aborting $operation operation.")
            signalEndOfOperation(context)
            return
        }
        
        // TODO: Make sure each operation ultimately leads to signalEndOfOperation()
        // TODO: Refactor this into an BleOperationType abstract or extension function
        when(operation)
        {
            is Disconnect -> with(operation) {
                Timber.w("Disconnecting from ${device.address}")
                gatt.close()
                deviceGattMap.remove(device)
                listeners.forEach { it.get()?.onDisconnect?.invoke(device) }
                signalEndOfOperation(context)
            }
            
            is CharacteristicWrite -> with(operation) {
                gatt.findCharacteristic(characteristicUuid)?.let { characteristic ->
                    characteristic.writeType = writeType
                    characteristic.value = payload
                    gatt.writeCharacteristic(characteristic)
                } ?: this@ConnectionManager.run {
                    Timber.e("Cannot find $characteristicUuid to write to")
                    signalEndOfOperation(context)
                }
            }
            
            is CharacteristicRead -> with(operation) {
                gatt.findCharacteristic(characteristicUuid)?.let { characteristic ->
                    gatt.readCharacteristic(characteristic)
                } ?: this@ConnectionManager.run {
                    Timber.e("Cannot find $characteristicUuid to read from")
                    signalEndOfOperation(context)
                }
            }
            
            is DescriptorWrite -> with(operation) {
                gatt.findDescriptor(descriptorUuid)?.let { descriptor ->
                    descriptor.value = payload
                    gatt.writeDescriptor(descriptor)
                } ?: this@ConnectionManager.run {
                    Timber.e("Cannot find $descriptorUuid to write to")
                    signalEndOfOperation(context)
                }
            }
            
            is DescriptorRead -> with(operation) {
                gatt.findDescriptor(descriptorUuid)?.let { descriptor ->
                    gatt.readDescriptor(descriptor)
                } ?: this@ConnectionManager.run {
                    Timber.e("Cannot find $descriptorUuid to read from")
                    signalEndOfOperation(context)
                }
            }
            
            is EnableNotifications -> with(operation) {
                gatt.findCharacteristic(characteristicUuid)?.let { characteristic ->
                    val cccdUuid = UUID.fromString(CCC_DESCRIPTOR_UUID)
                    val payload = when
                    {
                        characteristic.isIndicatable() -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                        characteristic.isNotifiable() -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        else -> error("${characteristic.uuid} doesn't support notifications/indications")
                    }
                    
                    characteristic.getDescriptor(cccdUuid)?.let { cccDescriptor ->
                        if(!gatt.setCharacteristicNotification(characteristic, true))
                        {
                            Timber.e("setCharacteristicNotification failed for ${characteristic.uuid}")
                            signalEndOfOperation(context)
                            return
                        }
                        
                        cccDescriptor.value = payload
                        gatt.writeDescriptor(cccDescriptor)
                    } ?: this@ConnectionManager.run {
                        Timber.e("${characteristic.uuid} doesn't contain the CCC descriptor!")
                        signalEndOfOperation(context)
                    }
                } ?: this@ConnectionManager.run {
                    Timber.e("Cannot find $characteristicUuid! Failed to enable notifications.")
                    signalEndOfOperation(context)
                }
            }
            
            is DisableNotifications -> with(operation) {
                gatt.findCharacteristic(characteristicUuid)?.let { characteristic ->
                    val cccdUuid = UUID.fromString(CCC_DESCRIPTOR_UUID)
                    characteristic.getDescriptor(cccdUuid)?.let { cccDescriptor ->
                        if(!gatt.setCharacteristicNotification(characteristic, false))
                        {
                            Timber.e("setCharacteristicNotification failed for ${characteristic.uuid}")
                            signalEndOfOperation(context)
                            return
                        }
                        
                        cccDescriptor.value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                        gatt.writeDescriptor(cccDescriptor)
                    } ?: this@ConnectionManager.run {
                        Timber.e("${characteristic.uuid} doesn't contain the CCC descriptor!")
                        signalEndOfOperation(context)
                    }
                } ?: this@ConnectionManager.run {
                    Timber.e("Cannot find $characteristicUuid! Failed to disable notifications.")
                    signalEndOfOperation(context)
                }
            }
            
            is MtuRequest -> with(operation) {
                gatt.requestMtu(mtu)
            }
        }
    }
    
    private val callback = object : BluetoothGattCallback()
    {
        private lateinit var context: Context // 宣告一個 Context 變數
        
        // 設定 context 的方法
        fun setContext(ctx: Context)
        {
            context = ctx
        }
        
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int)
        {
            val deviceAddress = gatt.device.address
            
            if(status == BluetoothGatt.GATT_SUCCESS)
            {
                if(newState == BluetoothProfile.STATE_CONNECTED)
                {
                    Timber.w("onConnectionStateChange: connected to $deviceAddress")
                    deviceGattMap[gatt.device] = gatt
                    Handler(Looper.getMainLooper()).post {
                        if(ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                        {
                            ActivityCompat.requestPermissions(context as Activity, arrayOf(Manifest.permission.BLUETOOTH_CONNECT), BLUETOOTH_CONNECT_PERMISSION_REQUEST_CODE)
                        }
                        gatt.discoverServices()
                    }
                }
                else if(newState == BluetoothProfile.STATE_DISCONNECTED)
                {
                    Timber.e("onConnectionStateChange: disconnected from $deviceAddress")
                    teardownConnection(gatt.device, context)
                }
            }
            else
            {
                Timber.e("onConnectionStateChange: status $status encountered for $deviceAddress!")
                if(pendingOperation is Connect)
                {
                    signalEndOfOperation(context)
                }
                teardownConnection(gatt.device, context)
            }
        }
        
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int)
        {
            with(gatt) {
                if(status == BluetoothGatt.GATT_SUCCESS)
                {
                    Timber.w("Discovered ${services.size} services for ${device.address}.")
                    printGattTable()
                    requestMtu(device, GATT_MAX_MTU_SIZE, context)
                    listeners.forEach { it.get()?.onConnectionSetupComplete?.invoke(this) }
                }
                else
                {
                    Timber.e("Service discovery failed due to status $status")
                    teardownConnection(gatt.device, context)
                }
            }
            
            if(pendingOperation is Connect)
            {
                signalEndOfOperation(context)
            }
        }
        
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int)
        {
            Timber.w("ATT MTU changed to $mtu, success: ${status == BluetoothGatt.GATT_SUCCESS}")
            listeners.forEach { it.get()?.onMtuChanged?.invoke(gatt.device, mtu) }
            
            if(pendingOperation is MtuRequest)
            {
                signalEndOfOperation(context)
            }
        }
        
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int)
        {
            with(characteristic) {
                when(status)
                {
                    BluetoothGatt.GATT_SUCCESS ->
                    {
                        Timber.i("Read characteristic $uuid | value: ${value.toHexString()}")
                        listeners.forEach {
                            it.get()?.onCharacteristicRead?.invoke(gatt.device, this)
                        }
                    }
                    
                    BluetoothGatt.GATT_READ_NOT_PERMITTED ->
                    {
                        Timber.e("Read not permitted for $uuid!")
                    }
                    
                    else ->
                    {
                        Timber.e("Characteristic read failed for $uuid, error: $status")
                    }
                }
            }
            
            if(pendingOperation is CharacteristicRead)
            {
                signalEndOfOperation(context)
            }
        }
        
        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int)
        {
            with(characteristic) {
                when(status)
                {
                    BluetoothGatt.GATT_SUCCESS ->
                    {
                        Timber.i("Wrote to characteristic $uuid | value: ${value.toHexString()}")
                        listeners.forEach {
                            it.get()?.onCharacteristicWrite?.invoke(gatt.device, this)
                        }
                    }
                    
                    BluetoothGatt.GATT_WRITE_NOT_PERMITTED ->
                    {
                        Timber.e("Write not permitted for $uuid!")
                    }
                    
                    else ->
                    {
                        Timber.e("Characteristic write failed for $uuid, error: $status")
                    }
                }
            }
            
            if(pendingOperation is CharacteristicWrite)
            {
                signalEndOfOperation(context)
            }
        }
        
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic)
        {
            with(characteristic) {
                Timber.i("Characteristic $uuid changed | value: ${value.toHexString()}")
                listeners.forEach { it.get()?.onCharacteristicChanged?.invoke(gatt.device, this) }
            }
        }
        
        override fun onDescriptorRead(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int)
        {
            with(descriptor) {
                when(status)
                {
                    BluetoothGatt.GATT_SUCCESS ->
                    {
                        Timber.i("Read descriptor $uuid | value: ${value.toHexString()}")
                        listeners.forEach { it.get()?.onDescriptorRead?.invoke(gatt.device, this) }
                    }
                    
                    BluetoothGatt.GATT_READ_NOT_PERMITTED ->
                    {
                        Timber.e("Read not permitted for $uuid!")
                    }
                    
                    else ->
                    {
                        Timber.e("Descriptor read failed for $uuid, error: $status")
                    }
                }
            }
            
            if(pendingOperation is DescriptorRead)
            {
                signalEndOfOperation(context)
            }
        }
        
        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int)
        {
            with(descriptor) {
                when(status)
                {
                    BluetoothGatt.GATT_SUCCESS ->
                    {
                        Timber.i("Wrote to descriptor $uuid | value: ${value.toHexString()}")
                        
                        if(isCccd())
                        {
                            onCccdWrite(gatt, value, characteristic)
                        }
                        else
                        {
                            listeners.forEach {
                                it.get()?.onDescriptorWrite?.invoke(gatt.device, this)
                            }
                        }
                    }
                    
                    BluetoothGatt.GATT_WRITE_NOT_PERMITTED ->
                    {
                        Timber.e("Write not permitted for $uuid!")
                    }
                    
                    else ->
                    {
                        Timber.e("Descriptor write failed for $uuid, error: $status")
                    }
                }
            }
            
            if(descriptor.isCccd() && (pendingOperation is EnableNotifications || pendingOperation is DisableNotifications))
            {
                signalEndOfOperation(context)
            }
            else if(!descriptor.isCccd() && pendingOperation is DescriptorWrite)
            {
                signalEndOfOperation(context)
            }
        }
        
        private fun onCccdWrite(gatt: BluetoothGatt, value: ByteArray, characteristic: BluetoothGattCharacteristic)
        {
            val charUuid = characteristic.uuid
            val notificationsEnabled = value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) || value.contentEquals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
            val notificationsDisabled = value.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)
            
            when
            {
                notificationsEnabled ->
                {
                    Timber.w("Notifications or indications ENABLED on $charUuid")
                    listeners.forEach {
                        it.get()?.onNotificationsEnabled?.invoke(gatt.device, characteristic)
                    }
                }
                
                notificationsDisabled ->
                {
                    Timber.w("Notifications or indications DISABLED on $charUuid")
                    listeners.forEach {
                        it.get()?.onNotificationsDisabled?.invoke(gatt.device, characteristic)
                    }
                }
                
                else ->
                {
                    Timber.e("Unexpected value ${value.toHexString()} on CCCD of $charUuid")
                }
            }
        }
        
    }
    
    fun setCallbackContext(ctx: Context)
    {
        callback.setContext(ctx)
    }/*
    private val broadcastReceiver = object : BroadcastReceiver()
    {
        override fun onReceive(context: Context, intent: Intent)
        {
            with(intent) {
                if(action == BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                {
                    val device = getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    val previousBondState =
                        getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1)
                    val bondState = getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
                    val bondTransition =
                        "${previousBondState.toBondStateDescription()} to " + bondState.toBondStateDescription()
                    Timber.w("${device?.address} bond state changed | $bondTransition")
                }
                
                /*
                    // Test for changing pairing method
                    if(BluetoothDevice.ACTION_PAIRING_REQUEST == intent.action)
                    {
                        val device: BluetoothDevice =
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)!!
                        val type = intent.getIntExtra(
                            BluetoothDevice.EXTRA_PAIRING_VARIANT, BluetoothDevice.ERROR)
                        if(type == BluetoothDevice.PAIRING_VARIANT_PIN)
                        {
                            val b = 0
                        }
                        else
                        {
                            val a = 0
                        }
                    }
                    
                */
            }
        }
        
        private fun Int.toBondStateDescription() = when(this)
        {
            BluetoothDevice.BOND_BONDED -> "BONDED"
            BluetoothDevice.BOND_BONDING -> "BONDING"
            BluetoothDevice.BOND_NONE -> "NOT BONDED"
            else -> "ERROR: $this"
        }
    }
    
     */
    
    fun BluetoothDevice.isConnected() = deviceGattMap.containsKey(this)
}
