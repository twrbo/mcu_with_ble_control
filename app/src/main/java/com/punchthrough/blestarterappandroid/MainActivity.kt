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

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.SearchView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.google.android.material.navigation.NavigationBarView
import com.punchthrough.blestarterappandroid.ble.ConnectionEventListener
import com.punchthrough.blestarterappandroid.ble.ConnectionManager
import kotlinx.android.synthetic.main.activity_main.scan_button
import kotlinx.android.synthetic.main.activity_main.scan_results_recycler_view
import kotlinx.android.synthetic.main.activity_main.searchView
import org.jetbrains.anko.alert
import org.jetbrains.anko.runOnUiThread
import timber.log.Timber

const val BLUETOOTH_SCAN_PERMISSION_REQUEST_CODE = 3
const val BLUETOOTH_CONNECT_PERMISSION_REQUEST_CODE = 4

class MainActivity : AppCompatActivity()
{
    
    /*******************************************
     * Properties
     *******************************************/
    private val bluetoothAdapter: BluetoothAdapter by lazy {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }
    
    private val bleScanner by lazy {
        bluetoothAdapter.bluetoothLeScanner
    }
    
    private val scanSettings =
        ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
    
    private var isScanning = false
        set(value)
        {
            field = value
            runOnUiThread { scan_button.text = if(value) "Stop Scan" else "Start Scan" }
        }
    
    private val scanResults = mutableListOf<ScanResult>()
    private val scanResultAdapter: ScanResultAdapter by lazy {
        ScanResultAdapter(scanResults) { result ->
            if(isScanning)
            {
                stopBleScan()
            }
            with(result.device) {
                Timber.w("Connecting to $address")
                ConnectionManager.connect(this, this@MainActivity)
            }
        }
    }

    /*******************************************
     * Activity function overrides
     *******************************************/
    
    override fun onCreate(savedInstanceState: Bundle?)
    {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        if(BuildConfig.DEBUG)
        {
            Timber.plant(Timber.DebugTree())
        }
        
        // OnClick Event
        scan_button.setOnClickListener { if(isScanning) stopBleScan() else startBleScan() }
        
        searchView.setOnQueryTextListener(object : androidx.appcompat.widget.SearchView.OnQueryTextListener
        {
            override fun onQueryTextSubmit(query: String?): Boolean
            {
                return true
            }
            
            override fun onQueryTextChange(newText: String?): Boolean
            {
                if(newText != null)
                {
                    scanResultAdapter.searchDevices(newText)
                }
                return true
            }
        })
        
        
        setupRecyclerView()
        
        NavigationBarView.OnItemSelectedListener { item ->
            when(item.itemId)
            {
                R.id.item_scan ->
                {
                    // Respond to navigation item 1 click
                    true
                }
                
                R.id.item_bonded ->
                {
                    // Respond to navigation item 2 click
                    true
                }
                
                else -> false
            }
        }
        
        // Transfer context in order to check permission
        ConnectionManager.setCallbackContext(this)
    }
    
    
    override fun onResume()
    {
        super.onResume()
        ConnectionManager.registerListener(connectionEventListener)
        if(!bluetoothAdapter.isEnabled)
        {
            promptEnableBluetooth()
        }
    
        // Transfer context in order to check permission
        ConnectionManager.setCallbackContext(this)
    }
    
    private val enableBluetoothLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            promptEnableBluetooth()
        }
    }
    
    /*******************************************
     * Private functions
     *******************************************/
    
    private fun promptEnableBluetooth()
    {
        if (!bluetoothAdapter.isEnabled) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            enableBluetoothLauncher.launch(enableBtIntent)
        }
    }
    
    @SuppressLint("NotifyDataSetChanged")
    private fun startBleScan()
    {
        scanResults.clear()
        scanResultAdapter.notifyDataSetChanged()
        if(ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
        {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH_SCAN), BLUETOOTH_SCAN_PERMISSION_REQUEST_CODE)
            return
        }
        bleScanner.startScan(null, scanSettings, scanCallback)
        isScanning = true
    }
    
    private fun stopBleScan()
    {
        if(ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
        {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH_SCAN), BLUETOOTH_SCAN_PERMISSION_REQUEST_CODE)
            return
        }
        bleScanner.stopScan(scanCallback)
        isScanning = false
    }
    
    private fun setupRecyclerView()
    {
        scan_results_recycler_view.apply {
            adapter = scanResultAdapter
            layoutManager = LinearLayoutManager(this@MainActivity, RecyclerView.VERTICAL, false)
            isNestedScrollingEnabled = false
        }
        
        val animator = scan_results_recycler_view.itemAnimator
        if(animator is SimpleItemAnimator)
        {
            animator.supportsChangeAnimations = false
        }
    }
    
    /*******************************************
     * Callback bodies
     *******************************************/
    
    private val scanCallback = object : ScanCallback()
    {
        override fun onScanResult(callbackType: Int, result: ScanResult)
        {
            val indexQuery = scanResults.indexOfFirst { it.device.address == result.device.address }
            if(indexQuery != -1)
            { // A scan result already exists with the same address
                scanResults[indexQuery] = result
                scanResultAdapter.notifyItemChanged(indexQuery)
            }
            else
            {
                with(result.device) {
                    if(ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                    {
                        ActivityCompat.requestPermissions(this@MainActivity, arrayOf(Manifest.permission.BLUETOOTH_CONNECT), BLUETOOTH_CONNECT_PERMISSION_REQUEST_CODE)
                        return
                    }
                    Timber.i("Found BLE device! Name: ${name ?: "Unnamed"}, address: $address")
                }
                scanResults.add(result)
                scanResultAdapter.notifyItemInserted(scanResults.size - 1)
            }
        }
        
        override fun onScanFailed(errorCode: Int)
        {
            Timber.e("onScanFailed: code $errorCode")
        }
    }
    
    private val connectionEventListener by lazy {
        ConnectionEventListener().apply {
            onConnectionSetupComplete = { gatt ->
                Intent(this@MainActivity, BleOperationsActivity::class.java).also {
                    it.putExtra(BluetoothDevice.EXTRA_DEVICE, gatt.device)
                    startActivity(it)
                }
                ConnectionManager.unregisterListener(this)
            }
            onDisconnect = {
                runOnUiThread {
                    alert {
                        title = "Disconnected"
                        message = "Disconnected or unable to connect to device."
                        positiveButton("OK") {}
                    }.show()
                }
            }
        }
    }
    
    /*******************************************
     * Extension functions
     *******************************************/
    
//    private fun Context.hasPermission(permissionType: String): Boolean
//    {
//        return ContextCompat.checkSelfPermission(
//            this, permissionType) == PackageManager.PERMISSION_GRANTED
//    }
//
//    private fun Activity.requestPermission(permission: String, requestCode: Int)
//    {
//        ActivityCompat.requestPermissions(this, arrayOf(permission), requestCode)
//    }
    
}


