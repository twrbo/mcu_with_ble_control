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

package com.punchthrough.blestarterappandroid

//import kotlinx.android.synthetic.main.activity_ble_operations.mtu_field
//import kotlinx.android.synthetic.main.activity_ble_operations.request_mtu_button
import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.RadioButton
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.punchthrough.blestarterappandroid.ble.ConnectionEventListener
import com.punchthrough.blestarterappandroid.ble.ConnectionManager
import com.punchthrough.blestarterappandroid.ble.isIndicatable
import com.punchthrough.blestarterappandroid.ble.isNotifiable
import com.punchthrough.blestarterappandroid.ble.isReadable
import com.punchthrough.blestarterappandroid.ble.isWritable
import com.punchthrough.blestarterappandroid.ble.isWritableWithoutResponse
import com.punchthrough.blestarterappandroid.ble.toArrayList
import com.punchthrough.blestarterappandroid.ble.toHexString
import kotlinx.android.synthetic.main.activity_ble_operations.characteristics_recycler_view
import kotlinx.android.synthetic.main.activity_ble_operations.log_scroll_view
import kotlinx.android.synthetic.main.activity_ble_operations.log_text_view
import kotlinx.android.synthetic.main.activity_ble_operations.tv_deviceMAC
import kotlinx.android.synthetic.main.activity_ble_operations.tv_deviceName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jetbrains.anko.alert
import org.jetbrains.anko.selector
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class BleOperationsActivity : AppCompatActivity()
{
    
    private lateinit var device: BluetoothDevice
    private val dateFormatter = SimpleDateFormat("HH:mm:ss", Locale.TAIWAN)
    private val characteristics by lazy {
        ConnectionManager.servicesOnDevice(device)?.flatMap { service ->
            service.characteristics ?: listOf()
        } ?: listOf()
    }
    private val characteristicProperties by lazy {
        characteristics.map { characteristic ->
            characteristic to mutableListOf<CharacteristicProperty>().apply {
                if(characteristic.isNotifiable()) add(CharacteristicProperty.Notifiable)
                if(characteristic.isIndicatable()) add(CharacteristicProperty.Indicatable)
                if(characteristic.isReadable()) add(CharacteristicProperty.Readable)
                if(characteristic.isWritable()) add(CharacteristicProperty.Writable)
                if(characteristic.isWritableWithoutResponse())
                {
                    add(CharacteristicProperty.WritableWithoutResponse)
                }
            }.toList()
        }.toMap()
    }
    private val characteristicAdapter: CharacteristicAdapter by lazy {
        CharacteristicAdapter(characteristics) { characteristic ->
            showCharacteristicOptions(characteristic)
        }
    }
    private var notifyingCharacteristics = mutableListOf<UUID>()
    
    override fun onCreate(savedInstanceState: Bundle?)
    {
        ConnectionManager.registerListener(connectionEventListener)
        super.onCreate(savedInstanceState)
        device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            ?: error("Missing BluetoothDevice from MainActivity!")
        
        setContentView(R.layout.activity_ble_operations)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowTitleEnabled(true)
            title = getString(R.string.ble_playground)
        }
        setupRecyclerView()
  
//        request_mtu_button.setOnClickListener {
//            if(mtu_field.text.isNotEmpty() && mtu_field.text.isNotBlank())
//            {
//                mtu_field.text.toString().toIntOrNull()?.let { mtu ->
//                    log("Requesting for MTU value of $mtu")
//                    ConnectionManager.requestMtu(device, mtu, this)
//                } ?: log("Invalid MTU value: ${mtu_field.text}")
//            }
//            else
//            {
//                log("Please specify a numeric value for desired ATT MTU (23-517)")
//            }
//            hideKeyboard()
//        }
        
        // Pass activity
        McuProtocol.initActivity(this)
    }
    
    override fun onDestroy()
    {
        ConnectionManager.unregisterListener(connectionEventListener)
        ConnectionManager.teardownConnection(device)
        super.onDestroy()
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean
    {
        when(item.itemId)
        {
            android.R.id.home ->
            {
                onBackPressed()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }
    
    
    private fun setupRecyclerView()
    {
        characteristics_recycler_view.apply {
            adapter = characteristicAdapter
            layoutManager = LinearLayoutManager(this@BleOperationsActivity, RecyclerView.VERTICAL, false)
            isNestedScrollingEnabled = false
        }
        
        // 0505 Add device name info
        if(ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
        {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH_CONNECT), BLUETOOTH_CONNECT_PERMISSION_REQUEST_CODE)
            return
        }
        tv_deviceName.text = device.name
        tv_deviceMAC.text = device.address
        
        val animator = characteristics_recycler_view.itemAnimator
        if(animator is SimpleItemAnimator)
        {
            animator.supportsChangeAnimations = false
        }
    }
    
    @SuppressLint("SetTextI18n")
    private fun log(message: String)
    {
        val formattedMessage = String.format("%s: %s", dateFormatter.format(Date()), message)
        runOnUiThread {
            val currentLogText = log_text_view.text
            log_text_view.text = "$currentLogText\n$formattedMessage"
            log_scroll_view.post { log_scroll_view.fullScroll(View.FOCUS_DOWN) }
        }
    }
    
    private fun showCharacteristicOptions(characteristic: BluetoothGattCharacteristic)
    {
        characteristicProperties[characteristic]?.let { properties ->
            selector("Select an action to perform", properties.map { it.action }) { _, i ->
                when(properties[i])
                {
                    CharacteristicProperty.Readable ->
                    {
                        log("Reading from ${characteristic.uuid}")
                        ConnectionManager.readCharacteristic(device, characteristic)
                    }
                    
                    CharacteristicProperty.Writable, CharacteristicProperty.WritableWithoutResponse ->
                    {
                        showWritePayloadDialog(characteristic)
                    }
                    
                    CharacteristicProperty.Notifiable, CharacteristicProperty.Indicatable ->
                    {
                        if(notifyingCharacteristics.contains(characteristic.uuid))
                        {
                            log("Disabling notifications on ${characteristic.uuid}")
                            ConnectionManager.disableNotifications(device, characteristic)
                        }
                        else
                        {
                            log("Enabling notifications on ${characteristic.uuid}")
                            ConnectionManager.enableNotifications(device, characteristic)
                        }
                    }
                }
            }
        }
    }
    
    @SuppressLint("InflateParams")
    private fun showWritePayloadDialog(characteristic: BluetoothGattCharacteristic)
    {
        val view = layoutInflater.inflate(R.layout.edittext_hex_payload, null)
        val editTextPayload = view.findViewById<EditText>(R.id.editText_payload)
        val radioButtonMCURead = view.findViewById<RadioButton>(R.id.radioButton_read)
        val editTextDataLength = view.findViewById<EditText>(R.id.editText_dataLength)
        
        // 增加 radioButtonMCURead 的點選事件監聽器
        radioButtonMCURead.setOnCheckedChangeListener { _, isChecked ->
            // 根據 isChecked 決定是否顯示 editTextDataLength
            editTextDataLength.visibility = if(isChecked) View.VISIBLE else View.GONE
        }
        
        // Enable notification
        val descriptors = characteristic.descriptors
        var descriptorUuid: UUID? = null
        for(descriptor in descriptors)
        {
            descriptorUuid = descriptor.uuid
        }
        var desValue: ByteArray? = null
        desValue = characteristic.getDescriptor(descriptorUuid).value
        if(desValue == null) ConnectionManager.enableNotifications(device, characteristic)
        
        alert("Operation") {
            customView = view
            isCancelable = false
            positiveButton("Yes") {
                // Clear log
                log_text_view.text=""
                ConnectionManager.setProgressBar(true)
                with(editTextPayload.text.toString()) {
                    if(isNotBlank() && isNotEmpty())
                    {
                        val bytes = hexToBytes()
//                        log("Writing to ${characteristic.uuid}: ${bytes.toHexString()}")
                        
                        
                        if(radioButtonMCURead.isChecked)
                        {
                            // Start MCU Read
                            val dataLengthText = editTextDataLength.text.toString()
                            if(dataLengthText.isNotBlank() && dataLengthText.isNotEmpty())
                            {
                                McuProtocol.setDataLength(dataLengthText.toInt())
                                CoroutineScope(Dispatchers.Main).launch {
                                    McuProtocol.read(device, characteristic, bytes)
                                }
                            }
                            else
                            {
                                log("Please enter the data length.")
                            }
                        }
                        else
                        {
                            editTextDataLength.visibility = View.INVISIBLE
                            
                            
                            // Start MCU Write
                            CoroutineScope(Dispatchers.Main).launch {
                                McuProtocol.write(device, characteristic, bytes)
                            }
                            
                        }
                    }
                    else
                    {
                        log("Please enter a hex payload to write to ${characteristic.uuid}")
                    }
                }
            }
            negativeButton("No") {}
        }.show()
        editTextPayload.showKeyboard()
        
        if(McuProtocol.getErrorCode() != McuProtocol.MCU_STATE_OK)
            log("The MCU state is not OK. ErrorCode: ${McuProtocol.getErrorCode()}")
    }
    
    val connectionEventListener by lazy {
        ConnectionEventListener().apply {
            onDisconnect = {
                runOnUiThread {
                    alert {
                        title = "Disconnected"
                        message = "Disconnected from device."
                        positiveButton("OK") { onBackPressed() }
                    }.show()
                }
            }
            
            onCharacteristicRead = { _, characteristic ->
                log("Read from ${characteristic.uuid}: ${characteristic.value.toHexString()}")
            }
            
            onCharacteristicWrite = { _, characteristic ->
//                log("Wrote to ${characteristic.uuid}")
            }
            
            onMtuChanged = { _, mtu ->
//                log("MTU updated to $mtu")
            }
            
            onCharacteristicChanged = { _, characteristic ->
//                log("Value changed on ${characteristic.uuid}: ${characteristic.value.toHexString()}")
                
                if(characteristic.value != null)
                {
                    McuProtocol.setReceivedData(characteristic.value.toArrayList())
                    McuProtocol.setTrigger()
                }
            }
            
            onNotificationsEnabled = { _, characteristic ->
//                log("Enabled notifications on ${characteristic.uuid}")
                notifyingCharacteristics.add(characteristic.uuid)
            }
            
            onNotificationsDisabled = { _, characteristic ->
//                log("Disabled notifications on ${characteristic.uuid}")
                notifyingCharacteristics.remove(characteristic.uuid)
            }
        }
    }
    
    private enum class CharacteristicProperty
    {
        Readable, Writable, WritableWithoutResponse, Notifiable, Indicatable;
        
        val action
            get() = when(this)
            {
                Readable -> "Read"
                Writable -> "Write"
                WritableWithoutResponse -> "Write Without Response"
                Notifiable -> "Toggle Notifications"
                Indicatable -> "Toggle Indications"
            }
    }
    
    private fun Activity.hideKeyboard()
    {
        hideKeyboard(currentFocus ?: View(this))
    }
    
    private fun Context.hideKeyboard(view: View)
    {
        val inputMethodManager = getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager
        inputMethodManager.hideSoftInputFromWindow(view.windowToken, 0)
    }
    
    private fun EditText.showKeyboard()
    {
        val inputMethodManager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        requestFocus()
        inputMethodManager.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }
    
    private fun String.hexToBytes() =
        this.chunked(2).map { it.uppercase(Locale.US).toInt(16).toByte() }.toByteArray()
}
