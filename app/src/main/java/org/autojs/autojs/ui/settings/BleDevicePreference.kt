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
import org.autojs.autojs.core.pref.Pref
import org.autojs.autojs.theme.preference.MaterialPreference
import org.autojs.autojs6.R
import java.util.concurrent.CopyOnWriteArraySet

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

    private val mainHandler = Handler(Looper.getMainLooper())
    private val btAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private val uiRefreshListener = Runnable { mainHandler.post { refreshUI() } }

    // Bound views
    private var deviceSpinner: Spinner? = null
    private var connectSwitch: SwitchCompat? = null

    // Device list
    private val devices: List<BluetoothDevice>
        get() = btAdapter?.bondedDevices?.sortedBy { it.name ?: it.address } ?: emptyList()

    companion object {
        @Volatile
        private var sharedBleService: IBleHidService? = null
        @Volatile
        private var isServiceBound = false
        private val callbacks = mutableSetOf<IBleHidCallback>()
        private val uiListeners = CopyOnWriteArraySet<Runnable>()

        @Volatile
        private var sharedConnectionState: BleConnectionStateHelper? = null

        private val sharedServiceConnection = object : android.content.ServiceConnection {
            override fun onServiceConnected(name: android.content.ComponentName?, service: android.os.IBinder?) {
                sharedBleService = IBleHidService.Stub.asInterface(service)
                isServiceBound = true
                callbacks.forEach { callback ->
                    try {
                        sharedBleService?.registerCallback(callback)
                    } catch (_: Exception) {}
                }
                sharedConnectionState?.syncFromService()
                notifyUiListeners()
            }

            override fun onServiceDisconnected(name: android.content.ComponentName?) {
                sharedBleService = null
                isServiceBound = false
            }
        }

        fun getConnectionState(context: Context): BleConnectionStateHelper {
            val appContext = context.applicationContext
            return sharedConnectionState ?: synchronized(this) {
                sharedConnectionState ?: BleConnectionStateHelper(
                    appContext,
                    logTag = "BleConnection",
                    onUiUpdate = { notifyUiListeners() },
                ).also { state ->
                    sharedConnectionState = state
                    registerCallback(state.bleCallback)
                    state.syncFromService()
                }
            }
        }

        fun addUiListener(listener: Runnable) {
            uiListeners.add(listener)
        }

        fun removeUiListener(listener: Runnable) {
            uiListeners.remove(listener)
        }

        private fun notifyUiListeners() {
            uiListeners.forEach { it.run() }
        }

        fun bindServiceIfNeeded(context: Context) {
            getConnectionState(context)
            if (isServiceBound) return
            try {
                val intent = Intent(context.applicationContext, BleHidService::class.java)
                context.applicationContext.bindService(intent, sharedServiceConnection, Context.BIND_AUTO_CREATE)
            } catch (_: Exception) {}
        }

        fun registerCallback(callback: IBleHidCallback) {
            callbacks.add(callback)
            try {
                sharedBleService?.registerCallback(callback)
            } catch (_: Exception) {}
        }

        fun unregisterCallback(callback: IBleHidCallback) {
            callbacks.remove(callback)
            try {
                sharedBleService?.unregisterCallback(callback)
            } catch (_: Exception) {}
        }

        fun getBleService(): IBleHidService? = sharedBleService
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        deviceSpinner = holder.findViewById(R.id.bleDeviceSpinner) as? Spinner
        deviceSpinner?.isFocusable = true
        deviceSpinner?.isClickable = true
        connectSwitch = holder.findViewById(R.id.bleConnectSwitch) as? SwitchCompat

        refreshSpinner()

        connectSwitch?.setOnCheckedChangeListener(null)
        connectSwitch?.setOnCheckedChangeListener { _: CompoundButton?, checked: Boolean ->
            val state = getConnectionState(context)
            if (checked && !state.isActive) {
                connectToSelectedDevice()
            } else if (!checked && state.isActive) {
                state.userDisconnect()
                refreshUI()
            }
        }

        deviceSpinner?.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                @Suppress("UNCHECKED_CAST")
                val list = parent?.tag as? List<BluetoothDevice>
                val device = list?.getOrNull(position)
                if (device != null) {
                    val previousAddr = Pref.getString(R.string.key_ble_device_address, "")
                    if (previousAddr.isNotEmpty() && previousAddr != device.address) {
                        getConnectionState(context).userDisconnect()
                    }
                    Pref.putString(R.string.key_ble_device_address, device.address)
                }
                refreshUI()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {
                refreshUI()
            }
        }

        bindServiceIfNeeded(context)
        addUiListener(uiRefreshListener)
        holder.itemView.post { refreshUI() }
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
        spinner.tag = list
        val savedAddr = Pref.getString(R.string.key_ble_device_address, "")
        val savedIdx = list.indexOfFirst { it.address == savedAddr }
        if (savedIdx >= 0) spinner.setSelection(savedIdx)
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
            Toast.makeText(context, context.getString(R.string.text_no_device_selected), Toast.LENGTH_SHORT).show()
            connectSwitch?.isChecked = false
            return
        }
        Pref.putString(R.string.key_ble_device_address, device.address)
        val started = getConnectionState(context).userConnect(device.address)
        if (!started) {
            Toast.makeText(context, context.getString(R.string.text_connection_cannot_be_established), Toast.LENGTH_SHORT).show()
            connectSwitch?.isChecked = false
        }
        refreshUI()
    }

    private fun refreshUI() {
        val state = getConnectionState(context)
        state.syncFromService()

        connectSwitch?.setOnCheckedChangeListener(null)
        connectSwitch?.isChecked = state.isActive
        connectSwitch?.setOnCheckedChangeListener { _: CompoundButton?, checked: Boolean ->
            if (checked && !state.isActive) {
                connectToSelectedDevice()
            } else if (!checked && state.isActive) {
                state.userDisconnect()
                refreshUI()
            }
        }

        updateSummary()
    }

    private fun updateSummary() {
        val device = getSelectedDevice()
        val addr = device?.address
        val name = device?.name ?: device?.address
        summary = getConnectionState(context).buildSubtitle(addr, name)
    }

    override fun onDetached() {
        removeUiListener(uiRefreshListener)
        deviceSpinner = null
        connectSwitch = null
        super.onDetached()
    }
}
