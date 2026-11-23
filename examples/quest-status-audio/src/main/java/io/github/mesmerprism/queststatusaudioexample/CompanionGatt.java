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
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** One local GATT server and at most one connected peer. */
@SuppressLint("MissingPermission") // The gate checks grants for start and every callback.
final class CompanionGatt {
    private static final UUID SERVICE = UUID.fromString("2e1fb001-2d5c-44ed-93de-9ecd76c520d6");
    private static final UUID WRITE = UUID.fromString("2e1fb002-2d5c-44ed-93de-9ecd76c520d6");
    private static final UUID READ = UUID.fromString("2e1fb003-2d5c-44ed-93de-9ecd76c520d6");
    private static final UUID SESSION_STATUS = UUID.fromString("2e1fb004-2d5c-44ed-93de-9ecd76c520d6");

    private static final class Peer {
        final BluetoothDevice device;
        Peer(BluetoothDevice device) { this.device = device; }
    }

    private final Handler main;
    private final CompanionProtocol protocol;
    private final Supplier<CompanionStatus> sampleStatus;
    private final BooleanSupplier live;
    private final Runnable ready;
    private final Runnable failed;
    private final Object peerLock = new Object();
    private volatile boolean closed;
    private volatile Peer announced;
    private Peer active;
    private BluetoothGattServer server;
    private BluetoothLeAdvertiser advertiser;

    CompanionGatt(Handler main, CompanionProtocol protocol, Supplier<CompanionStatus> sampleStatus,
            BooleanSupplier live, Runnable ready, Runnable failed) {
        this.main = main;
        this.protocol = protocol;
        this.sampleStatus = sampleStatus;
        this.live = live;
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
            server = manager.openGattServer(context, onGatt);
            if (server == null) return false;

            BluetoothGattService service = new BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY);
            BluetoothGattCharacteristic input = new BluetoothGattCharacteristic(WRITE,
                    BluetoothGattCharacteristic.PROPERTY_WRITE,
                    BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED);
            BluetoothGattCharacteristic output = new BluetoothGattCharacteristic(READ,
                    BluetoothGattCharacteristic.PROPERTY_READ,
                    BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED);
            BluetoothGattCharacteristic status = new BluetoothGattCharacteristic(SESSION_STATUS,
                    BluetoothGattCharacteristic.PROPERTY_READ,
                    BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED);
            return service.addCharacteristic(input) && service.addCharacteristic(output) &&
                    service.addCharacteristic(status) && server.addService(service);
        } catch (IllegalStateException | SecurityException unavailable) {
            return false;
        }
    }

    void close() {
        synchronized (peerLock) {
            closed = true;
            announced = null;
        }
        active = null;
        protocol.resetPeer();
        if (advertiser != null) {
            try { advertiser.stopAdvertising(onAdvertise); }
            catch (IllegalStateException | SecurityException ignored) { }
            advertiser = null;
        }
        if (server != null) {
            try { server.close(); }
            catch (IllegalStateException | SecurityException ignored) { }
            server = null;
        }
    }

    private boolean available() {
        return !closed && server != null && live.getAsBoolean();
    }

    private boolean current(Peer peer) {
        return peer != null && active == peer && announced == peer && available();
    }

    private Peer peerFor(BluetoothDevice device) {
        Peer current = announced;
        return current != null && current.device.equals(device) ? current : null;
    }

    private void advertise() {
        try {
            AdvertiseSettings settings = new AdvertiseSettings.Builder().setConnectable(true).build();
            AdvertiseData data = new AdvertiseData.Builder().addServiceUuid(new ParcelUuid(SERVICE)).build();
            advertiser.startAdvertising(settings, data, onAdvertise);
        } catch (IllegalStateException | SecurityException unavailable) {
            failed.run();
        }
    }

    private void reply(Peer peer, int requestId, int status, byte[] value) {
        if (!current(peer)) return;
        try { server.sendResponse(peer.device, requestId, status, 0, value); }
        catch (IllegalStateException | SecurityException unavailable) { failed.run(); }
    }

    private final AdvertiseCallback onAdvertise = new AdvertiseCallback() {
        @Override public void onStartSuccess(AdvertiseSettings settings) {
            main.post(() -> { if (available()) ready.run(); });
        }

        @Override public void onStartFailure(int errorCode) {
            main.post(() -> { if (available()) failed.run(); });
        }
    };

    private final BluetoothGattServerCallback onGatt = new BluetoothGattServerCallback() {
        @Override public void onServiceAdded(int status, BluetoothGattService service) {
            main.post(() -> {
                if (!available()) return;
                if (status == BluetoothGatt.GATT_SUCCESS && SERVICE.equals(service.getUuid())) advertise();
                else failed.run();
            });
        }

        @Override public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            synchronized (peerLock) {
                if (closed) return;
                Peer previous = announced;
                if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                    if (previous != null) {
                        if (!previous.device.equals(device)) {
                            main.post(() -> rejectOtherPeer(device));
                        }
                        return;
                    }
                    Peer pending = new Peer(device);
                    announced = pending;
                    main.post(() -> {
                        if (announced != pending || !available()) return;
                        protocol.resetPeer();
                        active = pending;
                    });
                } else if ((status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) &&
                        previous != null && previous.device.equals(device)) {
                    announced = null;
                    main.post(() -> {
                        if (active != previous) return;
                        protocol.resetPeer();
                        active = null;
                    });
                }
            }
        }

        @Override public void onCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset,
                BluetoothGattCharacteristic characteristic) {
            Peer requested = peerFor(device);
            main.post(() -> {
                if (!current(requested)) return;
                if (offset != 0) {
                    reply(requested, requestId, BluetoothGatt.GATT_INVALID_OFFSET, null);
                    return;
                }
                UUID characteristicId = characteristic.getUuid();
                byte[] result = null;
                if (READ.equals(characteristicId)) result = protocol.read();
                else if (SESSION_STATUS.equals(characteristicId)) result = protocol.readSessionStatus(sampleStatus);
                reply(requested, requestId, result != null ? BluetoothGatt.GATT_SUCCESS : BluetoothGatt.GATT_FAILURE, result);
            });
        }

        @Override public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId,
                BluetoothGattCharacteristic characteristic, boolean preparedWrite, boolean responseNeeded,
                int offset, byte[] value) {
            if (!responseNeeded) return;
            Peer requested = peerFor(device);
            // Copy only the small, closed packet before crossing threads.
            byte[] packet = value != null && value.length == 2 + CompanionProtocol.ID_LENGTH ? value.clone() : null;
            main.post(() -> {
                try {
                    if (!current(requested)) return;
                    boolean accepted = offset == 0 && !preparedWrite && packet != null &&
                            WRITE.equals(characteristic.getUuid()) && protocol.write(packet);
                    reply(requested, requestId, accepted ? BluetoothGatt.GATT_SUCCESS : BluetoothGatt.GATT_FAILURE, null);
                } finally {
                    if (packet != null) Arrays.fill(packet, (byte) 0);
                }
            });
        }

        @Override public void onExecuteWrite(BluetoothDevice device, int requestId, boolean execute) {
            Peer requested = peerFor(device);
            main.post(() -> reply(requested, requestId, BluetoothGatt.GATT_FAILURE, null));
        }
    };

    private void rejectOtherPeer(BluetoothDevice device) {
        if (!available() || peerFor(device) != null) return;
        try { server.cancelConnection(device); }
        catch (IllegalStateException | SecurityException unavailable) { failed.run(); }
    }
}
