package org.cagnulen.qdomyoszwift;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;

import java.util.UUID;

/**
 * One-shot diagnostic for consoles where the virtual bike never appears to other apps.
 *
 * Qt's peripheral backend logs "Starting to advertise." and the stack logs RegisterAdvertiser,
 * but neither reports whether the advertising set actually went on air - Qt swallows the
 * AdvertiseCallback result. On a console whose radio cannot advertise, that failure is silent
 * and looks identical to a working setup that nothing happens to be scanning for.
 *
 * This prints the adapter's advertising capabilities and then puts a minimal FTMS advertisement
 * on air itself, using a callback that reports the outcome, and takes it down again a few
 * seconds later so it never holds the advertising slot Qt needs.
 */
public class BleCapabilityProbe {

    private static final String TAG = "BleCapabilityProbe";
    private static final UUID FTMS = UUID.fromString("00001826-0000-1000-8000-00805f9b34fb");
    private static final long PROBE_MS = 8_000L;

    private static boolean alreadyRun = false;

    public static synchronized void run(Context context) {
        if (alreadyRun) {
            return;
        }
        alreadyRun = true;
        try {
            probe(context);
        } catch (Throwable t) {
            QLog.e(TAG, "probe failed", new Exception(t));
        }
    }

    private static void probe(Context context) {
        final BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            QLog.e(TAG, "no Bluetooth adapter");
            return;
        }
        QLog.i(TAG, "adapter enabled=" + adapter.isEnabled() + " name=" + adapter.getName());
        QLog.i(TAG, "multipleAdvertisementSupported=" + adapter.isMultipleAdvertisementSupported()
                + " offloadedFiltering=" + adapter.isOffloadedFilteringSupported()
                + " offloadedScanBatching=" + adapter.isOffloadedScanBatchingSupported());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            QLog.i(TAG, "le2MPhy=" + adapter.isLe2MPhySupported()
                    + " leCodedPhy=" + adapter.isLeCodedPhySupported()
                    + " leExtendedAdvertising=" + adapter.isLeExtendedAdvertisingSupported()
                    + " maxAdvertisingDataLength=" + adapter.getLeMaximumAdvertisingDataLength());
        }

        final BluetoothLeAdvertiser advertiser = adapter.getBluetoothLeAdvertiser();
        if (advertiser == null) {
            QLog.e(TAG, "getBluetoothLeAdvertiser() returned null - this radio cannot advertise");
            return;
        }

        final AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(true)
                .setTimeout(0)
                .build();
        // Deliberately minimal: no device name, so a DATA_TOO_LARGE result means the radio is the
        // limit rather than the payload.
        final AdvertiseData data = new AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .addServiceUuid(new ParcelUuid(FTMS))
                .build();

        final AdvertiseCallback callback = new AdvertiseCallback() {
            @Override
            public void onStartSuccess(AdvertiseSettings settingsInEffect) {
                QLog.i(TAG, "PROBE ADVERTISING STARTED - this radio can advertise (mode="
                        + settingsInEffect.getMode() + " txPower=" + settingsInEffect.getTxPowerLevel()
                        + " connectable=" + settingsInEffect.isConnectable() + ")");
            }

            @Override
            public void onStartFailure(int errorCode) {
                QLog.e(TAG, "PROBE ADVERTISING FAILED code=" + errorCode + " (" + describe(errorCode) + ")");
            }
        };

        QLog.i(TAG, "starting probe advertisement for " + (PROBE_MS / 1000) + "s");
        advertiser.startAdvertising(settings, data, callback);
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    advertiser.stopAdvertising(callback);
                    QLog.i(TAG, "probe advertisement stopped, slot released");
                } catch (Exception e) {
                    QLog.w(TAG, "stopAdvertising failed: " + e.getMessage());
                }
            }
        }, PROBE_MS);
    }

    private static String describe(int errorCode) {
        switch (errorCode) {
            case AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE:
                return "DATA_TOO_LARGE";
            case AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS:
                return "TOO_MANY_ADVERTISERS";
            case AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED:
                return "ALREADY_STARTED";
            case AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR:
                return "INTERNAL_ERROR";
            case AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED:
                return "FEATURE_UNSUPPORTED";
            default:
                return "unknown";
        }
    }
}
