package io.github.mesmerprism.queststatusaudioexample;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.os.Handler;
import android.os.ParcelUuid;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** Only the installation identity and LAN route are discoverable over BLE. */
@SuppressLint("MissingPermission") // The wearer grants Nearby Devices before startup.
final class CompanionGatt {
    private static final UUID SERVICE = UUID.fromString("2e1fb001-2d5c-44ed-93de-9ecd76c520d6");
    private static final UUID DISCOVERY = UUID.fromString("2e1fb005-2d5c-44ed-93de-9ecd76c520d6");
    private static final int MAX_ATTRIBUTE_BYTES = 512;
    private static final int MAX_PEERS = 4;

    private static final class Peer {
        int mtu = 23;
        byte[] snapshot;
    }

    private final Handler main;
    private final Supplier<byte[]> discovery;
    private final Runnable ready;
    private final Runnable failed;
    private final Map<BluetoothDevice, Peer> peers = new HashMap<>();
    private BluetoothGattServer server;
    private BluetoothLeAdvertiser advertiser;

    CompanionGatt(Handler main, Supplier<byte[]> discovery, Runnable ready, Runnable failed) {
        this.main = main;
        this.discovery = discovery;
        this.ready = ready;
        this.failed = failed;
    }

    boolean start(Context context) {
        try {
            BluetoothManager manager = context.getSystemService(BluetoothManager.class);
            BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
            if (adapter == null || !adapter.isEnabled()) return false;
            advertiser = adapter.getBluetoothLeAdvertiser();
            if (advertiser == null) return false;
            server = manager.openGattServer(context, callbacks);
            if (server == null) return false;
            BluetoothGattService service = new BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY);
            BluetoothGattCharacteristic address = new BluetoothGattCharacteristic(DISCOVERY,
                    BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ);
            return service.addCharacteristic(address) && server.addService(service);
        } catch (IllegalStateException | SecurityException unavailable) {
            return false;
        }
    }

    void close() {
        peers.clear();
        if (advertiser != null) {
            try { advertiser.stopAdvertising(advertising); }
            catch (IllegalStateException | SecurityException ignored) { }
            advertiser = null;
        }
        if (server != null) {
            try { server.close(); }
            catch (IllegalStateException | SecurityException ignored) { }
            server = null;
        }
    }

    private void advertise() {
        try {
            AdvertiseSettings settings = new AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                    .setConnectable(true).build();
            AdvertiseData data = new AdvertiseData.Builder().addServiceUuid(new ParcelUuid(SERVICE)).build();
            advertiser.startAdvertising(settings, data, advertising);
        } catch (IllegalStateException | SecurityException unavailable) {
            failed.run();
        }
    }

    private void read(BluetoothDevice device, int requestId, int offset, UUID characteristic) {
        Peer peer = peers.get(device);
        if (server == null || peer == null) return;
        if (!DISCOVERY.equals(characteristic)) {
            reply(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null);
            return;
        }
        if (offset == 0) peer.snapshot = discovery.get();
        byte[] snapshot = peer.snapshot;
        if (snapshot == null || snapshot.length > MAX_ATTRIBUTE_BYTES) {
            reply(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null);
        } else if (offset < 0 || offset > snapshot.length) {
            reply(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null);
        } else {
            int end = Math.min(snapshot.length, offset + peer.mtu - 1);
            reply(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, Arrays.copyOfRange(snapshot, offset, end));
        }
    }

    private void reply(BluetoothDevice device, int requestId, int status, int offset, byte[] value) {
        try { server.sendResponse(device, requestId, status, offset, value); }
        catch (IllegalStateException | SecurityException unavailable) { failed.run(); }
    }

    private final AdvertiseCallback advertising = new AdvertiseCallback() {
        @Override public void onStartSuccess(AdvertiseSettings settings) {
            main.post(() -> { if (server != null) ready.run(); });
        }

        @Override public void onStartFailure(int errorCode) {
            main.post(() -> { if (server != null) failed.run(); });
        }
    };

    private final BluetoothGattServerCallback callbacks = new BluetoothGattServerCallback() {
        @Override public void onServiceAdded(int status, BluetoothGattService service) {
            main.post(() -> {
                if (server == null) return;
                if (status == BluetoothGatt.GATT_SUCCESS && SERVICE.equals(service.getUuid())) advertise();
                else failed.run();
            });
        }

        @Override public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            main.post(() -> {
                if (server == null) return;
                if (status != BluetoothGatt.GATT_SUCCESS || newState != BluetoothProfile.STATE_CONNECTED) {
                    peers.remove(device);
                } else if (peers.size() < MAX_PEERS || peers.containsKey(device)) {
                    peers.put(device, new Peer());
                } else {
                    try { server.cancelConnection(device); }
                    catch (IllegalStateException | SecurityException unavailable) { failed.run(); }
                }
            });
        }

        @Override public void onMtuChanged(BluetoothDevice device, int mtu) {
            main.post(() -> {
                Peer peer = peers.get(device);
                if (peer != null && mtu >= 23 && mtu <= 517) peer.mtu = mtu;
            });
        }

        @Override public void onCharacteristicReadRequest(
                BluetoothDevice device, int requestId, int offset, BluetoothGattCharacteristic characteristic) {
            main.post(() -> read(device, requestId, offset, characteristic.getUuid()));
        }
    };
}
