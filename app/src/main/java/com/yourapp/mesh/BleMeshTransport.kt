package com.yourapp.mesh

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.PowerManager
import android.os.SystemClock
import kotlin.random.Random
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
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
    private var peerConnectionListener: ((String, Boolean) -> Unit)? = null

    // Outgoing GATT client connections (devices WE connected to as client) — send via write
    private val clientConnections = ConcurrentHashMap<String, BluetoothGatt>()

    // Incoming connections (devices that connected to OUR server) — send via notify
    private val serverConnectedDevices = ConcurrentHashMap<String, BluetoothDevice>()

    private val knownDeviceAddresses = ConcurrentHashMap.newKeySet<String>()
    private val connectingDevices = ConcurrentHashMap.newKeySet<String>()
    // Peers that connected to us AND subscribed to notifications = link works in both directions
    private val subscribed = ConcurrentHashMap.newKeySet<String>()
    private val subscribeAttempts = ConcurrentHashMap<String, Int>()

    // BLE only carries ~20 bytes per packet by default, but an encoded MeshMessage is 80+ bytes.
    // So every message is split into chunks, sent one at a time, and reassembled on receive.
    private var sender: ExecutorService? = null
    private val opDone = Semaphore(0)
    private val clientMtu = ConcurrentHashMap<String, Int>()
    private val serverMtu = ConcurrentHashMap<String, Int>()
    private val reassembly = ConcurrentHashMap<String, ByteArrayOutputStream>()
    private val peerCount = AtomicInteger(0)

    private val handler = Handler(Looper.getMainLooper())
    private val lastAttempt = ConcurrentHashMap<String, Long>()
    @Volatile private var wantRunning = false
    @Volatile private var lastScanStart = 0L
    private val scanRetryPending = java.util.concurrent.atomic.AtomicBoolean(false)

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("0000fdaa-0000-1000-8000-00805f9b34fb")
        val MESSAGE_CHARACTERISTIC_UUID: UUID = UUID.fromString("0000fdab-0000-1000-8000-00805f9b34fb")
        // Standard Client Characteristic Configuration Descriptor — required to enable notify
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val FLAG_FIRST = 1
        private const val FLAG_LAST = 2
        private const val DEFAULT_MTU = 23
        private const val REQUESTED_MTU = 247
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val RETRY_GAP_MS = 4_000L
    }

    override fun setDebugListener(listener: ((String) -> Unit)?) {
        debugListener = listener
    }

    override fun setPeerConnectionListener(listener: ((String, Boolean) -> Unit)?) {
        peerConnectionListener = listener
    }

    /** Tells the service about a peer change, then re-tunes scanning (searching vs connected). */
    private fun notifyPeer(address: String, connected: Boolean) {
        peerConnectionListener?.invoke(address, connected)
        handler.postDelayed({ if (wantRunning) startScanning() }, 1_500)
    }

    /**
     * Some phones silently stop scanning or advertising after a while, which looks exactly like
     * "stuck searching". Refresh both regularly; faster while no peer is connected.
     */
    private val maintenance = object : Runnable {
        override fun run() {
            if (!wantRunning || !isActive) return
            startScanning()
            refreshAdvertising()
            handler.postDelayed(this, if (peerCount.get() == 0) 30_000L else 90_000L)
        }
    }

    private fun debug(msg: String) {
        debugListener?.invoke("[BLE] $msg")
    }

    override fun start(onMessageReceived: (MeshMessage) -> Unit) {
        wantRunning = true
        if (adapter == null || !adapter.isEnabled) {
            debug("Bluetooth is off - will retry in 5s")
            // If the user turns Bluetooth on later, pick up automatically
            handler.postDelayed({ if (wantRunning && !isActive) start(onMessageReceived) }, 5_000)
            return
        }
        onMessage = onMessageReceived
        advertiser = adapter.bluetoothLeAdvertiser
        scanner = adapter.bluetoothLeScanner

        if (advertiser == null) debug("WARNING: advertiser is null (device may not support BLE peripheral mode)")
        if (scanner == null) debug("WARNING: scanner is null")

        sender = Executors.newSingleThreadExecutor()
        startGattServer()
        startAdvertising()
        startScanning()
        isActive = true
        handler.postDelayed(maintenance, 20_000)
        debug("Transport started")
    }

    override fun stop() {
        wantRunning = false
        handler.removeCallbacksAndMessages(null)
        lastAttempt.clear()
        advertiser?.stopAdvertising(advertiseCallback)
        scanner?.stopScan(scanCallback)
        gattServer?.close()
        clientConnections.values.forEach { it.close() }
        clientConnections.clear()
        serverConnectedDevices.clear()
        knownDeviceAddresses.clear()
        connectingDevices.clear()
        subscribed.clear()
        subscribeAttempts.clear()
        clientMtu.clear()
        serverMtu.clear()
        reassembly.clear()
        sender?.shutdownNow()
        sender = null
        isActive = false
    }

    override fun send(message: MeshMessage) {
        val bytes = MeshCodec.encode(message)
        val exec = sender ?: return
        try {
            exec.execute { sendBlocking(bytes) }
        } catch (_: RejectedExecutionException) {
            // shutting down
        }
    }

    /** Runs on the single sender thread: one GATT operation at a time, waiting for each to finish. */
    @Suppress("DEPRECATION")
    private fun sendBlocking(bytes: ByteArray) {
        var sentCount = 0

        // Direction 1: we are the client of these peers -> write
        for ((address, gatt) in clientConnections) {
            val characteristic = gatt.getService(SERVICE_UUID)?.getCharacteristic(MESSAGE_CHARACTERISTIC_UUID)
            if (characteristic == null) {
                debug("Characteristic not found on peer $address")
                continue
            }
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            var ok = true
            for (c in chunk(bytes, (clientMtu[address] ?: DEFAULT_MTU) - 3)) {
                opDone.drainPermits()
                characteristic.value = c
                if (!gatt.writeCharacteristic(characteristic) || !opDone.tryAcquire(2, TimeUnit.SECONDS)) {
                    ok = false
                    break
                }
            }
            if (ok) sentCount++ else debug("Write to $address failed")
        }

        // Direction 2: these peers connected to us -> notify
        val characteristic = gattServer?.getService(SERVICE_UUID)?.getCharacteristic(MESSAGE_CHARACTERISTIC_UUID)
        if (characteristic != null) {
            for ((address, device) in serverConnectedDevices) {
                if (!subscribed.contains(address)) continue // not ready yet
                var ok = true
                for (c in chunk(bytes, (serverMtu[address] ?: DEFAULT_MTU) - 3)) {
                    opDone.drainPermits()
                    characteristic.value = c
                    var queued = gattServer?.notifyCharacteristicChanged(device, characteristic, false) ?: false
                    if (!queued) {
                        Thread.sleep(150)
                        queued = gattServer?.notifyCharacteristicChanged(device, characteristic, false) ?: false
                    }
                    if (!queued || !opDone.tryAcquire(2, TimeUnit.SECONDS)) {
                        ok = false
                        break
                    }
                }
                if (ok) sentCount++ else debug("Notify to $address failed (peer may not have subscribed)")
            }
        }

        if (sentCount == 0) {
            debug("send() called but no connected peers (client=${clientConnections.size}, server=${serverConnectedDevices.size})")
        } else {
            debug("Sent to $sentCount peer(s)")
        }
    }

    /** Split data into packets of at most [maxPacket] bytes, each prefixed with a 1-byte first/last flag. */
    private fun chunk(data: ByteArray, maxPacket: Int): List<ByteArray> {
        val body = (maxPacket - 1).coerceAtLeast(1)
        val out = ArrayList<ByteArray>()
        var off = 0
        do {
            val end = minOf(off + body, data.size)
            var flags = 0
            if (off == 0) flags = flags or FLAG_FIRST
            if (end == data.size) flags = flags or FLAG_LAST
            val packet = ByteArray(1 + end - off)
            packet[0] = flags.toByte()
            System.arraycopy(data, off, packet, 1, end - off)
            out.add(packet)
            off = end
        } while (off < data.size)
        return out
    }

    /** Collect incoming packets per peer and decode once the last packet arrives. */
    private fun onChunk(key: String, packet: ByteArray) {
        if (packet.isEmpty()) return
        val flags = packet[0].toInt()
        val buf = reassembly.getOrPut(key) { ByteArrayOutputStream() }
        var full: ByteArray? = null
        synchronized(buf) {
            if (flags and FLAG_FIRST != 0) buf.reset()
            buf.write(packet, 1, packet.size - 1)
            if (flags and FLAG_LAST != 0) {
                full = buf.toByteArray()
                buf.reset()
            }
        }
        full?.let { bytes ->
            val msg = MeshCodec.decode(bytes)
            if (msg == null) debug("Dropped undecodable message (${bytes.size} bytes)") else onMessage?.invoke(msg)
        }
    }

    override fun connectedPeerCount(): Int = peerCount.get()

    // ---------- GATT SERVER (receives client writes, sends notifies) ----------

    private fun startGattServer() {
        gattServer = bluetoothManager.openGattServer(context, gattServerCallback)

        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val characteristic = BluetoothGattCharacteristic(
            MESSAGE_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        // CCCD descriptor is required for the client to subscribe to notifications
        val cccd = BluetoothGattDescriptor(
            CCCD_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        characteristic.addDescriptor(cccd)
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
                onChunk("w:${device.address}", value)
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (descriptor.uuid == CCCD_UUID) {
                val enabled = value.isNotEmpty() && value[0].toInt() == 1
                if (enabled) {
                    subscribed.add(device.address)
                    debug("Server: ${device.address} subscribed - link ready both ways")
                    if (knownDeviceAddresses.add(device.address)) {
                        peerCount.incrementAndGet()
                        notifyPeer(device.address, true)
                    }
                } else {
                    subscribed.remove(device.address)
                }
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            serverMtu[device.address] = mtu
            debug("Server: MTU for ${device.address} = $mtu")
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            opDone.release()
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                serverConnectedDevices[device.address] = device
                debug("Server: peer connected ${device.address}, waiting for it to subscribe")
                startAdvertising() // some phones stop advertising once connected
                // A central that never subscribes would be a one-way link - drop it so it retries cleanly
                handler.postDelayed({
                    if (serverConnectedDevices.containsKey(device.address) &&
                        !subscribed.contains(device.address)
                    ) {
                        debug("Server: ${device.address} never subscribed, dropping")
                        gattServer?.cancelConnection(device)
                    }
                }, 10_000)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                serverConnectedDevices.remove(device.address)
                subscribed.remove(device.address)
                serverMtu.remove(device.address)
                reassembly.remove("w:${device.address}")
                if (knownDeviceAddresses.remove(device.address)) {
                    peerCount.decrementAndGet()
                    notifyPeer(device.address, false)
                }
                debug("Server: peer disconnected ${device.address}")
                startAdvertising()
            }
        }
    }

    // ---------- ADVERTISING ----------

    private fun refreshAdvertising() {
        try { advertiser?.stopAdvertising(advertiseCallback) } catch (_: Exception) {}
        startAdvertising()
    }

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
            if (errorCode == ADVERTISE_FAILED_ALREADY_STARTED) return // harmless
            debug("Advertising FAILED, error code: $errorCode - retrying")
            if (wantRunning) handler.postDelayed({ startAdvertising() }, 3_000)
        }
    }

    // ---------- SCANNING (discovers peers, connects as client, subscribes to notify) ----------

    private fun startScanning() {
        val sc = scanner ?: return
        val now = SystemClock.elapsedRealtime()
        val wait = 5_000 - (now - lastScanStart)
        if (wait > 0) {
            // Android limits scan restarts (5 per 30s): try again right after the gap instead of dropping it
            if (scanRetryPending.compareAndSet(false, true)) {
                handler.postDelayed({
                    scanRetryPending.set(false)
                    if (wantRunning) startScanning()
                }, wait)
            }
            return
        }
        lastScanStart = now

        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val screenOn = pm.isInteractive
        val searching = peerCount.get() == 0

        // Screen on: scan wide open and fast (finds peers most reliably).
        // Screen off: Android only allows filtered scans, so filter on our service UUID.
        val mode = if (searching && screenOn) ScanSettings.SCAN_MODE_LOW_LATENCY
        else ScanSettings.SCAN_MODE_BALANCED
        val settings = ScanSettings.Builder().setScanMode(mode).build()
        val filters = if (screenOn) null else listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        )

        try { sc.stopScan(scanCallback) } catch (_: Exception) {}
        try {
            sc.startScan(filters, settings, scanCallback)
            debug("Scan started (${if (screenOn) "open" else "filtered"}, ${if (searching) "searching" else "connected"})")
        } catch (e: Exception) {
            debug("Scan start failed: ${e.message}")
        }
    }

    /** Turn on notifications so the peer can send to us. Retries because the stack is often busy. */
    @Suppress("DEPRECATION")
    private fun subscribe(gatt: BluetoothGatt, address: String) {
        val characteristic = gatt.getService(SERVICE_UUID)?.getCharacteristic(MESSAGE_CHARACTERISTIC_UUID)
        val cccd = characteristic?.getDescriptor(CCCD_UUID)
        if (characteristic == null || cccd == null) {
            debug("Peer $address has no message characteristic, dropping")
            gatt.disconnect()
            return
        }
        gatt.setCharacteristicNotification(characteristic, true)
        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (!gatt.writeDescriptor(cccd)) retrySubscribe(gatt, address)
    }

    private fun retrySubscribe(gatt: BluetoothGatt, address: String) {
        val n = (subscribeAttempts[address] ?: 0) + 1
        subscribeAttempts[address] = n
        if (n > 4) {
            debug("Could not subscribe on $address, dropping to retry")
            gatt.disconnect()
            return
        }
        handler.postDelayed({
            if (wantRunning && !clientConnections.containsKey(address)) subscribe(gatt, address)
        }, 600)
    }

    private fun connectToDevice(device: BluetoothDevice) {
        if (!wantRunning || clientConnections.containsKey(device.address)) {
            connectingDevices.remove(device.address)
            return
        }
        debug("Connecting to matching peer ${device.address}")
        val gatt = device.connectGatt(context, false, object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    debug("Client connection state: $newState for ${device.address}")
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        // Ask for a bigger MTU first; discoverServices() runs in onMtuChanged
                        if (!gatt.requestMtu(REQUESTED_MTU)) gatt.discoverServices()
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        connectingDevices.remove(device.address)
                        clientMtu.remove(device.address)
                        subscribeAttempts.remove(device.address)
                        reassembly.remove("n:${device.address}")
                        clientConnections.remove(device.address)
                        gatt.close()
                        if (knownDeviceAddresses.remove(device.address)) {
                            peerCount.decrementAndGet()
                            notifyPeer(device.address, false)
                        }
                    }
                }

                override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                    if (status == BluetoothGatt.GATT_SUCCESS) clientMtu[device.address] = mtu
                    debug("Client: MTU for ${device.address} = $mtu (status=$status)")
                    gatt.discoverServices()
                }

                override fun onCharacteristicWrite(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    status: Int
                ) {
                    opDone.release()
                }

                override fun onDescriptorWrite(
                    gatt: BluetoothGatt,
                    descriptor: BluetoothGattDescriptor,
                    status: Int
                ) {
                    if (descriptor.uuid != CCCD_UUID) return
                    debug("Notify subscription on ${device.address}: status=$status")
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        subscribeAttempts.remove(device.address)
                        // Only now is the link usable both ways, so only now do we call it connected
                        clientConnections[device.address] = gatt
                        if (knownDeviceAddresses.add(device.address)) {
                            peerCount.incrementAndGet()
                            notifyPeer(device.address, true)
                        }
                    } else {
                        retrySubscribe(gatt, device.address)
                    }
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    debug("Services discovered on ${device.address}, status=$status")
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        gatt.disconnect() // cleanup + a fresh attempt on the next scan result
                        return
                    }
                    subscribe(gatt, device.address)
                }

                // Android 13+ delivers notifications here, with the value passed in directly
                override fun onCharacteristicChanged(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    value: ByteArray
                ) {
                    if (characteristic.uuid == MESSAGE_CHARACTERISTIC_UUID) {
                        debug("Received notify from ${device.address}, ${value.size} bytes")
                        onChunk("n:${device.address}", value)
                    }
                }

                // Android 12 and below
                @Deprecated("Deprecated in Java")
                override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                    if (characteristic.uuid == MESSAGE_CHARACTERISTIC_UUID) {
                        val value = characteristic.value
                        debug("Received notify from ${device.address}, ${value?.size ?: 0} bytes")
                        value?.let { onChunk("n:${device.address}", it) }
                    }
                }
            }, BluetoothDevice.TRANSPORT_LE)

        if (gatt == null) {
            connectingDevices.remove(device.address)
            return
        }
        // A connection that never completes would block this peer forever - give up and retry
        handler.postDelayed({
            if (!clientConnections.containsKey(device.address) && connectingDevices.contains(device.address)) {
                debug("Connect timeout for ${device.address}, retrying later")
                try { gatt.disconnect() } catch (_: Exception) {}
                try { gatt.close() } catch (_: Exception) {}
                connectingDevices.remove(device.address)
            }
        }, CONNECT_TIMEOUT_MS)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val uuids = result.scanRecord?.serviceUuids

            if (uuids == null || !uuids.contains(ParcelUuid(SERVICE_UUID))) return
            if (clientConnections.containsKey(device.address)) return
            if (!connectingDevices.add(device.address)) return // already connecting

            val now = SystemClock.elapsedRealtime()
            if (now - (lastAttempt[device.address] ?: 0L) < RETRY_GAP_MS) {
                connectingDevices.remove(device.address)
                return
            }
            lastAttempt[device.address] = now
            // Small random delay so two phones don't connect to each other at the same instant
            handler.postDelayed({ connectToDevice(device) }, Random.nextLong(0, 600))
        }

        override fun onScanFailed(errorCode: Int) {
            debug("SCAN FAILED, error code: $errorCode")
        }
    }
}
