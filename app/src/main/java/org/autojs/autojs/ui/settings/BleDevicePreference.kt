package org.autojs.autojs.ui.settings

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import android.widget.ArrayAdapter
import android.widget.CompoundButton
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import androidx.preference.PreferenceViewHolder
import com.smartfinger.blehidhost.ipc.IBleHidCallback
import com.smartfinger.blehidhost.ipc.IBleHidService
import com.smartfinger.blehidhost.service.BleHidService
import org.autojs.autojs.theme.preference.MaterialPreference
import org.autojs.autojs6.R

class BleDevicePreference : MaterialPreference {

    init {
        layoutResource = R.layout.preference_ble_device
    }

    @Suppress("unused")
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int, defStyleRes: Int) : super(context, attrs, defStyleAttr, defStyleRes)

    @Suppress("unused")
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    @Suppress("unused")
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    @Suppress("unused")
    constructor(context: Context) : super(context)

    private var bleService: IBleHidService? = null
    private var isServiceBound = false
    private var isConnecting = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val btAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()

    // Bound views
    private var deviceSpinner: Spinner? = null
    private var connectSwitch: SwitchCompat? = null

    // Device list
    private val devices: List<BluetoothDevice>
        get() = btAdapter?.bondedDevices?.sortedBy { it.name ?: it.address } ?: emptyList()

    private val serviceConnection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName?, service: android.os.IBinder?) {
            bleService = IBleHidService.Stub.asInterface(service)
            isServiceBound = true
            try {
                bleService?.registerCallback(bleCallback)
            } catch (_: Exception) {}
            mainHandler.post { refreshUI() }
        }

        override fun onServiceDisconnected(name: android.content.ComponentName?) {
            bleService = null
            isServiceBound = false
            mainHandler.post { refreshUI() }
        }
    }

    private val bleCallback = object : IBleHidCallback.Stub() {
        override fun onConnectionStateChanged(state: Int, macAddress: String?) {
            mainHandler.post { refreshUI() }
        }

        override fun onError(macAddress: String?, message: String?) {
            mainHandler.post {
                Toast.makeText(context, "BLE: $message", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        deviceSpinner = holder.findViewById(R.id.bleDeviceSpinner) as? Spinner
        connectSwitch = holder.findViewById(R.id.bleConnectSwitch) as? SwitchCompat

        // Populate spinner
        refreshSpinner()

        // Switch listener
        connectSwitch?.setOnCheckedChangeListener(null)
        connectSwitch?.setOnCheckedChangeListener { _: CompoundButton?, checked: Boolean ->
            if (checked && !isConnecting) {
                connectToSelectedDevice()
            } else if (!checked) {
                disconnectDevice()
                refreshUI()
            }
        }

        // Spinner selection — just update summary, no action until switch is toggled
        deviceSpinner?.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateSummary()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {
                updateSummary()
            }
        }

        bindBleService()
        refreshUI()
    }

    private fun refreshSpinner() {
        val spinner = deviceSpinner ?: return
        val list = devices
        val items = list.map { "${it.name ?: "Unknown"}" }
        val adapter = object : ArrayAdapter<String>(context, android.R.layout.simple_spinner_dropdown_item, items) {
            override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val view = super.getDropDownView(position, convertView, parent)
                (view as? android.widget.TextView)?.text = items[position]
                return view
            }
        }
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        spinner.tag = list // store devices list in tag
    }

    private fun getSelectedDevice(): BluetoothDevice? {
        val spinner = deviceSpinner ?: return null
        @Suppress("UNCHECKED_CAST")
        val list = spinner.tag as? List<BluetoothDevice> ?: return null
        val pos = spinner.selectedItemPosition
        return list.getOrNull(pos)
    }

    private fun connectToSelectedDevice() {
        val device = getSelectedDevice()
        if (device == null) {
            Toast.makeText(context, "No device selected", Toast.LENGTH_SHORT).show()
            connectSwitch?.isChecked = false
            return
        }
        try {
            bleService?.connectToDevice(device.address)
            isConnecting = true
            refreshUI()
        } catch (e: Exception) {
            Toast.makeText(context, "Connect failed: ${e.message}", Toast.LENGTH_SHORT).show()
            connectSwitch?.isChecked = false
        }
    }

    private fun disconnectDevice() {
        try {
            bleService?.disconnect()
        } catch (_: Exception) {}
        isConnecting = false
    }

    private fun refreshUI() {
        val connected = try {
            bleService?.isConnected ?: false
        } catch (_: Exception) {
            false
        }
        isConnecting = false

        connectSwitch?.setOnCheckedChangeListener(null)
        connectSwitch?.isChecked = connected
        connectSwitch?.setOnCheckedChangeListener { _: CompoundButton?, checked: Boolean ->
            if (checked && !isConnecting) {
                connectToSelectedDevice()
            } else if (!checked) {
                disconnectDevice()
                refreshUI()
            }
        }

        updateSummary()
    }

    private fun updateSummary() {
        val device = getSelectedDevice()
        val deviceName = device?.name ?: device?.address ?: "none"
        val connected = try {
            bleService?.isConnected ?: false
        } catch (_: Exception) {
            false
        }
        summary = when {
            connected -> "Connected to $deviceName"
            isConnecting -> "Connecting to $deviceName..."
            else -> "Tap switch to connect to $deviceName"
        }
    }

    private fun bindBleService() {
        if (isServiceBound) return
        try {
            val intent = Intent(context, BleHidService::class.java)
            context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        } catch (_: Exception) {}
    }

    private fun unbindBleService() {
        if (isServiceBound) {
            try { bleService?.unregisterCallback(bleCallback) } catch (_: Exception) {}
            try { context.unbindService(serviceConnection) } catch (_: Exception) {}
            bleService = null
            isServiceBound = false
        }
    }

    override fun onDetached() {
        unbindBleService()
        deviceSpinner = null
        connectSwitch = null
        super.onDetached()
    }
}
