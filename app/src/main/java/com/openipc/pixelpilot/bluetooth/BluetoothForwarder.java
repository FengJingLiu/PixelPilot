package com.openipc.pixelpilot.bluetooth;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import com.openipc.mavlink.MavlinkRawForwarder;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bluetooth MAVLink forwarder supporting both BLE (NUS) and legacy SPP.
 */
public class BluetoothForwarder implements MavlinkRawForwarder {

    public interface Listener {
        void onConnected(String address, String name, boolean ble);

        void onConnectionFailed(String address, String message, boolean ble);

        void onDisconnected(String address, String message, boolean ble);
    }

    private static final String TAG = "pixelpilot";
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final UUID NUS_SERVICE_UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E");
    private static final UUID NUS_TX_UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E");
    private static final UUID NUS_RX_UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E");
    private static final int DEFAULT_MTU = 185;

    public enum Mode {
        AUTO,
        BLE,
        SPP
    }

    private final Context context;
    private final Listener listener;
    private final HandlerThread ioThread;
    private final Handler ioHandler;
    private final AtomicBoolean connecting = new AtomicBoolean(false);

    // SPP
    private android.bluetooth.BluetoothSocket socket;
    private OutputStream outputStream;

    // BLE
    private BluetoothGatt bleGatt;
    private BluetoothGattCharacteristic bleRxCharacteristic;
    private final Queue<byte[]> writeQueue = new ArrayDeque<>();
    private boolean bleWriting = false;
    private int maxBleChunk = 20;

    private String currentAddress;
    private String currentName;
    private boolean currentIsBle = false;
    private Runnable bleScanTimeoutRunnable;
    private boolean suppressNotifications = false;

    private Mode preferredMode = Mode.AUTO;

    private final ScanCallback scanCallback;
    private final BluetoothGattCallback gattCallback;

    public BluetoothForwarder(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;

        ioThread = new HandlerThread("MavlinkBtForwarder");
        ioThread.start();
        ioHandler = new Handler(ioThread.getLooper());

        scanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                BluetoothDevice device = result.getDevice();
                if (device == null || device.getAddress() == null) {
                    return;
                }
                if (currentAddress != null && !currentAddress.equals(device.getAddress())) {
                    return;
                }
                Log.d(TAG, "BLE scan matched device: " + device.getAddress());
                stopBleScan();
                connectGatt(device);
            }

            @Override
            public void onBatchScanResults(java.util.List<ScanResult> results) {
                for (ScanResult result : results) {
                    onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, result);
                }
            }

            @Override
            public void onScanFailed(int errorCode) {
                Log.e(TAG, "BLE scan failed: " + errorCode);
                notifyFailed(currentAddress, "BLE 扫描失败: " + errorCode, true);
                connecting.set(false);
            }
        };

        gattCallback = new BluetoothGattCallback() {
            @Override
            public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.e(TAG, "BLE connection state change error: " + status);
                    notifyFailed(currentAddress, "BLE 连接错误: " + status, true);
                    closeBle();
                    connecting.set(false);
                    return;
                }
                if (newState == BluetoothGatt.STATE_CONNECTED) {
                    Log.d(TAG, "BLE connected, discovering services");
                    currentIsBle = true;
                    currentName = gatt.getDevice().getName();
                    gatt.discoverServices();
                } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                    Log.d(TAG, "BLE disconnected");
                    notifyDisconnected(currentAddress, "BLE已断开", true);
                    closeBle();
                    connecting.set(false);
                }
            }

            @Override
            public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    notifyFailed(currentAddress, "BLE 服务发现失败: " + status, true);
                    closeBle();
                    connecting.set(false);
                    return;
                }
                BluetoothGattService service = gatt.getService(NUS_SERVICE_UUID);
                if (service == null) {
                    notifyFailed(currentAddress, "未找到 NUS 服务", true);
                    closeBle();
                    connecting.set(false);
                    return;
                }
                BluetoothGattCharacteristic rx = service.getCharacteristic(NUS_RX_UUID);
                if (rx == null) {
                    notifyFailed(currentAddress, "未找到 NUS RX 特征", true);
                    closeBle();
                    connecting.set(false);
                    return;
                }
                bleRxCharacteristic = rx;
                if (gatt.requestMtu(DEFAULT_MTU)) {
                    Log.d(TAG, "BLE request MTU " + DEFAULT_MTU);
                } else {
                    Log.w(TAG, "BLE request MTU failed, fallback");
                    maxBleChunk = rx.getWriteType() == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE ?
                            182 : 20;
                    finishBleConnection();
                }
            }

            @Override
            public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "BLE MTU changed: " + mtu);
                    maxBleChunk = Math.max(20, mtu - 3);
                } else {
                    maxBleChunk = 20;
                }
                finishBleConnection();
            }

            @Override
            public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
                if (characteristic.getUuid().equals(NUS_RX_UUID)) {
                    handleBleWriteCompleted(status == BluetoothGatt.GATT_SUCCESS);
                }
            }
        };
    }

    private void finishBleConnection() {
        connecting.set(false);
        if (listener != null) {
            listener.onConnected(currentAddress, currentName != null ? currentName : currentAddress, true);
        }
    }

    public void setPreferredMode(Mode mode) {
        this.preferredMode = mode;
    }

    public Mode getPreferredMode() {
        return preferredMode;
    }

    public void connect(String address, boolean blePreferred) {
        // 强制 BLE 模式：忽略传入的 blePreferred，内部只尝试 BLE 连接
        ioHandler.post(() -> connectInternal(address, true));
    }

    public void disconnect() {
        disconnect(true);
    }

    public void disconnect(boolean notify) {
        ioHandler.post(() -> {
            suppressNotifications = !notify;
            disconnectInternal();
            suppressNotifications = false;
        });
    }

    public String getCurrentAddress() {
        return currentAddress;
    }

    public String getCurrentName() {
        return currentName;
    }

    public boolean isCurrentBle() {
        return currentIsBle;
    }

    private void connectInternal(String address, boolean blePreferred) {
        if (address == null || address.isEmpty()) {
            notifyFailed(address, "目标地址为空", blePreferred);
            return;
        }
        if (connecting.getAndSet(true)) {
            return;
        }

        disconnectInternal();

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            connecting.set(false);
            notifyFailed(address, "蓝牙不可用", blePreferred);
            return;
        }

        currentAddress = address;
        currentIsBle = false;
        currentName = null;

        // 仅使用 BLE，不再回退到 SPP
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            startBleScan(address);
            return;
        } else {
            notifyFailed(address, "系统版本过低，BLE 不可用", true);
            connecting.set(false);
        }
    }

    private void connectSpp(String address) {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        try {
            BluetoothDevice device = adapter.getRemoteDevice(address);
            android.bluetooth.BluetoothSocket tmpSocket = device.createRfcommSocketToServiceRecord(SPP_UUID);
            adapter.cancelDiscovery();
            tmpSocket.connect();
            OutputStream stream = tmpSocket.getOutputStream();
            socket = tmpSocket;
            outputStream = stream;
            currentAddress = address;
            currentName = device.getName();
            currentIsBle = false;
            connecting.set(false);
            if (listener != null) {
                listener.onConnected(currentAddress, currentName, false);
            }
        } catch (SecurityException se) {
            Log.e(TAG, "SPP connect security exception", se);
            connecting.set(false);
            notifyFailed(address, "缺少蓝牙权限", false);
        } catch (IOException e) {
            Log.e(TAG, "SPP connect error", e);
            connecting.set(false);
            notifyFailed(address, e.getMessage() != null ? e.getMessage() : "连接失败", false);
            disconnectInternal();
        }
    }

    private void startBleScan(String address) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            // 仅 BLE：旧系统不支持 BLE 扫描，直接失败
            notifyFailed(address, "系统版本过低，BLE 扫描不可用", true);
            connecting.set(false);
            return;
        }
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            Log.w(TAG, "BLE scanner null");
            notifyFailed(address, "BLE 扫描器不可用", true);
            connecting.set(false);
            return;
        }
        Log.d(TAG, "Starting BLE scan for address: " + address);
        currentAddress = address;
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
        java.util.List<ScanFilter> filters = Collections.singletonList(
                new ScanFilter.Builder().setDeviceAddress(address).build()
        );
        scanner.startScan(filters, settings, scanCallback);
        bleScanTimeoutRunnable = () -> {
            Log.w(TAG, "BLE scan timeout");
            stopBleScan();
            if (connecting.get()) {
                notifyFailed(address, "BLE 扫描超时", true);
                connecting.set(false);
            }
        };
        ioHandler.postDelayed(bleScanTimeoutRunnable, 8000);
    }

    private void stopBleScan() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return;
        }
        if (bleScanTimeoutRunnable != null) {
            ioHandler.removeCallbacks(bleScanTimeoutRunnable);
            bleScanTimeoutRunnable = null;
        }
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
        if (scanner != null) {
            try {
                scanner.stopScan(scanCallback);
            } catch (Exception ignored) {
            }
        }
    }

    private void connectGatt(BluetoothDevice device) {
        Log.d(TAG, "Connecting to BLE device via GATT: " + device.getAddress());
        try {
            bleGatt = device.connectGatt(context, false, gattCallback);
        } catch (SecurityException e) {
            Log.e(TAG, "BLE connectGatt security exception", e);
            notifyFailed(device.getAddress(), "缺少蓝牙权限", true);
            connecting.set(false);
        }
    }

    private void disconnectInternal() {
        stopBleScan();
        closeBle();
        try {
            if (outputStream != null) {
                outputStream.close();
            }
        } catch (IOException ignored) {
        } finally {
            outputStream = null;
        }
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
        } finally {
            socket = null;
        }
        if (currentAddress != null && listener != null && !suppressNotifications) {
            listener.onDisconnected(currentAddress, "连接已断开", currentIsBle);
        }
        currentAddress = null;
        currentName = null;
        currentIsBle = false;
        connecting.set(false);
    }

    private void closeBle() {
        if (bleGatt != null) {
            try {
                bleGatt.disconnect();
            } catch (Exception ignored) {
            }
            try {
                bleGatt.close();
            } catch (Exception ignored) {
            }
        }
        bleGatt = null;
        bleRxCharacteristic = null;
        writeQueue.clear();
        bleWriting = false;
    }

    private void notifyFailed(String address, String message, boolean ble) {
        if (listener != null && !suppressNotifications) {
            listener.onConnectionFailed(address, message, ble);
        }
    }

    private void notifyDisconnected(String address, String message, boolean ble) {
        if (listener != null && !suppressNotifications) {
            listener.onDisconnected(address, message, ble);
        }
    }

    @Override
    public void onMavlinkRaw(byte[] data, int length) {
        if (length <= 0) {
            return;
        }
        final byte[] copy = Arrays.copyOf(data, length);
        ioHandler.post(() -> {
            if (currentIsBle) {
                enqueueBleWrite(copy);
            } else {
                writeSpp(copy);
            }
        });
    }

    private void writeSpp(byte[] payload) {
        if (outputStream == null) {
            return;
        }
        try {
            outputStream.write(payload);
            outputStream.flush();
        } catch (IOException e) {
            Log.e(TAG, "Bluetooth SPP write error", e);
            disconnectInternal();
        }
    }

    private void enqueueBleWrite(byte[] payload) {
        if (bleGatt == null || bleRxCharacteristic == null) {
            return;
        }
        int offset = 0;
        while (offset < payload.length) {
            int chunk = Math.min(maxBleChunk, payload.length - offset);
            byte[] slice = Arrays.copyOfRange(payload, offset, offset + chunk);
            writeQueue.add(slice);
            offset += chunk;
        }
        if (!bleWriting) {
            bleWriting = true;
            writeNextBleChunk();
        }
    }

    private void writeNextBleChunk() {
        if (bleGatt == null || bleRxCharacteristic == null) {
            bleWriting = false;
            return;
        }
        byte[] chunk = writeQueue.poll();
        if (chunk == null) {
            bleWriting = false;
            return;
        }
        bleRxCharacteristic.setValue(chunk);
        bleRxCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        boolean initiated = bleGatt.writeCharacteristic(bleRxCharacteristic);
        if (!initiated) {
            Log.e(TAG, "BLE writeCharacteristic failed to initiate");
            handleBleWriteCompleted(false);
        }
    }

    private void handleBleWriteCompleted(boolean success) {
        if (!success) {
            Log.e(TAG, "BLE write failed");
            notifyDisconnected(currentAddress, "BLE 写入失败", true);
            closeBle();
            connecting.set(false);
            return;
        }
        if (writeQueue.isEmpty()) {
            bleWriting = false;
        } else {
            writeNextBleChunk();
        }
    }

    public void release() {
        ioHandler.post(() -> {
            suppressNotifications = true;
            disconnectInternal();
            suppressNotifications = false;
            ioThread.quitSafely();
        });
    }
}
