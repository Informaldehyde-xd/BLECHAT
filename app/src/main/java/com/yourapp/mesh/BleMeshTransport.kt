package com.yourapp.mesh

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.ParcelUuid
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

@SuppressLint("MissingPermission") // permissions are requested at runtime in MainActivity
class BleMeshTransport(private val context: Context) : MeshTransport {

    override val name = "BLE"
    override var isActive = false
        private set

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager.adapter
    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private var gattServer: BluetoothGattServer? = null
    private var onMessage: ((MeshMessage) -> Unit)? = null
    private var debugListener: ((String) -> Unit)? = null

    // Connected outgoing GATT client connections, keyed by device address
    private val clientConnections = ConcurrentHashMap<String, BluetoothGatt>()
    private val knownDeviceAddresses = ConcurrentHashMap.newKeySet<String>()
    private val peerCount = AtomicInteger(0)

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("0000fdaa-0000-1000-8000-00805f9b34fb")
        val MESSAGE_CHARACTERISTIC_UUID: UUID = UUID.fromString("0000fdab-0000-1000-8000-00805f9b34fb")
    }

    override fun setDebugListener(listener: ((String) -> Unit)?) {
        debugListener = listener
    }

    private fun debug(msg: String) {
        debugListener?.invoke(msg)
    }

    override fun start(onMessageReceived: (MeshMessage) -> Unit) {
        if (adapter == null || !adapter.isEnabled) {
            debug("Bluetooth adapter unavailable or disabled")
            return
        }
        onMessage = onMessageReceived
        advertiser = adapter.bluetoothLeAdvertiser
        scanner = adapter.bluetoothLeScanner

        if (advertiser == null) debug("WARNING: advertiser is null (device may not support BLE peripheral mode)")
        if (scanner == null) debug("WARNING: scanner is null")

        startGattServer()
        startAdvertising()
        startScanning()
        isActive = true
        debug("Transport started")
    }

    override fun stop() {
        advertiser?.stopAdvertising(advertiseCallback)
        scanner?.stopScan(scanCallback)
        gattServer?.close()
        clientConnections.values.forEach { it.close() }
        clientConnections.clear()
        knownDeviceAddresses.clear()
        isActive = false
    }

    override fun send(message: MeshMessage) {
        val bytes = MeshCodec.encode(message)
        if (clientConnections.isEmpty()) {
            debug("send() called but no connected peers")
        }
        clientConnections.values.forEach { gatt ->
            val service = gatt.getService(SERVICE_UUID)
            val characteristic = service?.getCharacteristic(MESSAGE_CHARACTERISTIC_UUID)
            if (characteristic != null) {
                characteristic.value = bytes
                gatt.writeCharacteristic(characteristic)
            } else {
                debug("Characteristic not found on peer ${gatt.device.address}")
            }
        }
    }

    override fun connectedPeerCount(): Int = peerCount.get()

    // ---------- GATT SERVER (receives messages from peers) ----------

    private fun startGattServer() {
        gattServer = bluetoothManager.openGattServer(context, gattServerCallback)

        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val characteristic = BluetoothGattCharacteristic(
            MESSAGE_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        service.addCharacteristic(characteristic)
        gattServer?.addService(service)
        debug("GATT server started, addService called")
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            debug("Received write from ${device.address}, ${value.size} bytes")
            if (characteristic.uuid == MESSAGE_CHARACTERISTIC_UUID) {
                MeshCodec.decode(value)?.let { onMessage?.invoke(it) }
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (knownDeviceAddresses.add(device.address)) peerCount.incrementAndGet()
                debug("Server: peer connected ${device.address}")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (knownDeviceAddresses.remove(device.address)) peerCount.decrementAndGet()
                debug("Server: peer disconnected ${device.address}")
            }
        }
    }

    // ---------- ADVERTISING ----------

    private fun startAdvertising() {
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()

        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .setIncludeDeviceName(false)
            .build()

        advertiser?.startAdvertising(settings, data, advertiseCallback)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            debug("Advertising started successfully")
        }

        override fun onStartFailure(errorCode: Int) {
            isActive = false
            debug("Advertising FAILED, error code: $errorCode")
        }
    }

    // ---------- SCANNING (discovers peers, connects as client) ----------

    private fun startScanning() {
        // No filter for now, to confirm raw scanning works across devices/chipsets
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner?.startScan(null, settings, scanCallback)
        debug("Scan started (no filter)")
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val uuids = result.scanRecord?.serviceUuids
            debug("Scan hit: ${device.address} uuids=$uuids")

            // Only attempt connection if it advertises our service
            if (uuids == null || !uuids.contains(ParcelUuid(SERVICE_UUID))) return
            if (clientConnections.containsKey(device.address)) return

            debug("Connecting to matching peer ${device.address}")
            device.connectGatt(context, false, object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    debug("Client connection state: $newState for ${device.address}")
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        gatt.discoverServices()
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        clientConnections.remove(device.address)
                        gatt.close()
                    }
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    debug("Services discovered on ${device.address}, status=$status")
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        clientConnections[device.address] = gatt
                    }
                }
            })
        }

        override fun onScanFailed(errorCode: Int) {
            debug("SCAN FAILED, error code: $errorCode")
        }
    }
}
