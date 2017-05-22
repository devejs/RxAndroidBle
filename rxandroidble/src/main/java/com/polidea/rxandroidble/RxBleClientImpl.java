package com.polidea.rxandroidble;

import android.bluetooth.BluetoothDevice;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import com.polidea.rxandroidble.RxBleAdapterStateObservable.BleAdapterState;
import com.polidea.rxandroidble.exceptions.BleScanException;
import com.polidea.rxandroidble.internal.RxBleDeviceProvider;
import com.polidea.rxandroidble.internal.scan.RxBleInternalScanResult;
import com.polidea.rxandroidble.internal.scan.RxBleInternalScanResultLegacy;
import com.polidea.rxandroidble.internal.RxBleRadio;
import com.polidea.rxandroidble.internal.operations.RxBleRadioOperationScanLegacy;
import com.polidea.rxandroidble.internal.scan.ScanSetup;
import com.polidea.rxandroidble.internal.scan.ScanSetupBuilder;
import com.polidea.rxandroidble.internal.util.LocationServicesStatus;
import com.polidea.rxandroidble.internal.util.RxBleAdapterWrapper;
import com.polidea.rxandroidble.internal.util.UUIDUtil;

import com.polidea.rxandroidble.scan.ScanFilter;
import com.polidea.rxandroidble.scan.ScanResult;
import com.polidea.rxandroidble.scan.ScanSettings;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

import javax.inject.Inject;
import javax.inject.Named;

import rx.Completable;
import rx.Observable;
import rx.android.schedulers.AndroidSchedulers;
import rx.functions.Action0;
import rx.functions.Func0;
import rx.functions.Func1;

class RxBleClientImpl extends RxBleClient {

    private final RxBleRadio rxBleRadio;
    private final UUIDUtil uuidUtil;
    private final RxBleDeviceProvider rxBleDeviceProvider;
    private final ScanSetupBuilder scanSetupBuilder;
    private final Func1<RxBleInternalScanResult, ScanResult> internalToExternalScanResultMapFunction;
    private final ExecutorService executorService;
    private final Map<Set<UUID>, Observable<RxBleScanResult>> queuedScanOperations = new HashMap<>();
    private final RxBleAdapterWrapper rxBleAdapterWrapper;
    private final Observable<BleAdapterState> rxBleAdapterStateObservable;
    private final LocationServicesStatus locationServicesStatus;

    @Inject
    RxBleClientImpl(RxBleAdapterWrapper rxBleAdapterWrapper,
                    RxBleRadio rxBleRadio,
                    Observable<BleAdapterState> adapterStateObservable,
                    UUIDUtil uuidUtil,
                    LocationServicesStatus locationServicesStatus,
                    RxBleDeviceProvider rxBleDeviceProvider,
                    ScanSetupBuilder scanSetupBuilder,
                    Func1<RxBleInternalScanResult, ScanResult> internalToExternalScanResultMapFunction,
                    @Named(ClientComponent.NamedSchedulers.GATT_CALLBACK) ExecutorService executorService) {
        this.uuidUtil = uuidUtil;
        this.rxBleRadio = rxBleRadio;
        this.rxBleAdapterWrapper = rxBleAdapterWrapper;
        this.rxBleAdapterStateObservable = adapterStateObservable;
        this.locationServicesStatus = locationServicesStatus;
        this.rxBleDeviceProvider = rxBleDeviceProvider;
        this.scanSetupBuilder = scanSetupBuilder;
        this.internalToExternalScanResultMapFunction = internalToExternalScanResultMapFunction;
        this.executorService = executorService;
    }

    @Override
    protected void finalize() throws Throwable {
        super.finalize();
        executorService.shutdown();
    }

    @Override
    public RxBleDevice getBleDevice(@NonNull String macAddress) {
        guardBluetoothAdapterAvailable();
        return rxBleDeviceProvider.getBleDevice(macAddress);
    }

    @Override
    public Set<RxBleDevice> getBondedDevices() {
        guardBluetoothAdapterAvailable();
        Set<RxBleDevice> rxBleDevices = new HashSet<>();
        Set<BluetoothDevice> bluetoothDevices = rxBleAdapterWrapper.getBondedDevices();
        for (BluetoothDevice bluetoothDevice : bluetoothDevices) {
            rxBleDevices.add(getBleDevice(bluetoothDevice.getAddress()));
        }

        return rxBleDevices;
    }

    @Override
    public Observable<ScanResult> scanBleDevices(final ScanSettings scanSettings, final ScanFilter... scanFilters) {
        return verifyScanPreconditions().andThen(Observable.defer(new Func0<Observable<ScanResult>>() {
            @Override
            public Observable<ScanResult> call() {
                final ScanSetup scanSetup = scanSetupBuilder.build(scanSettings, scanFilters);
                return rxBleRadio.queue(scanSetup.scanOperation)
                        .doOnUnsubscribe(new Action0() {
                            @Override
                            public void call() {
                                scanSetup.scanOperation.stop();
                            }
                        })
                        .unsubscribeOn(AndroidSchedulers.mainThread())
                        .compose(scanSetup.scanOperationBehaviourEmulatorTransformer)
                        .map(internalToExternalScanResultMapFunction);
            }
        }));
    }


    public Observable<RxBleScanResult> scanBleDevices(@Nullable final UUID... filterServiceUUIDs) {
        return verifyScanPreconditions().andThen(Observable.defer(new Func0<Observable<RxBleScanResult>>() {
            @Override
            public Observable<RxBleScanResult> call() {
                return initializeScan(filterServiceUUIDs);
            }
        }));
    }

    private Completable verifyScanPreconditions() {
        return Completable.fromAction(new Action0() {
            @Override
            public void call() {
                if (!rxBleAdapterWrapper.hasBluetoothAdapter()) {
                    throw new BleScanException(BleScanException.BLUETOOTH_NOT_AVAILABLE);
                } else if (!rxBleAdapterWrapper.isBluetoothEnabled()) {
                    throw new BleScanException(BleScanException.BLUETOOTH_DISABLED);
                } else if (!locationServicesStatus.isLocationPermissionOk()) {
                    throw new BleScanException(BleScanException.LOCATION_PERMISSION_MISSING);
                } else if (!locationServicesStatus.isLocationProviderOk()) {
                    throw new BleScanException(BleScanException.LOCATION_SERVICES_DISABLED);
                }
            }
        });
    }

    private Observable<RxBleScanResult> initializeScan(@Nullable UUID[] filterServiceUUIDs) {
        final Set<UUID> filteredUUIDs = uuidUtil.toDistinctSet(filterServiceUUIDs);

        synchronized (queuedScanOperations) {
            Observable<RxBleScanResult> matchingQueuedScan = queuedScanOperations.get(filteredUUIDs);

            if (matchingQueuedScan == null) {
                matchingQueuedScan = createScanOperationApi18(filterServiceUUIDs);
                queuedScanOperations.put(filteredUUIDs, matchingQueuedScan);
            }

            return matchingQueuedScan;
        }
    }

    private <T> Observable<T> bluetoothAdapterOffExceptionObservable() {
        return rxBleAdapterStateObservable
                .filter(new Func1<BleAdapterState, Boolean>() {
                    @Override
                    public Boolean call(BleAdapterState state) {
                        return state != BleAdapterState.STATE_ON;
                    }
                })
                .first()
                .flatMap(new Func1<BleAdapterState, Observable<? extends T>>() {
                    @Override
                    public Observable<? extends T> call(BleAdapterState status) {
                        return Observable.error(new BleScanException(BleScanException.BLUETOOTH_DISABLED));
                    }
                });
    }

    private RxBleScanResult convertToPublicScanResult(RxBleInternalScanResultLegacy scanResult) {
        final BluetoothDevice bluetoothDevice = scanResult.getBluetoothDevice();
        final RxBleDevice bleDevice = getBleDevice(bluetoothDevice.getAddress());
        return new RxBleScanResult(bleDevice, scanResult.getRssi(), scanResult.getScanRecord());
    }

    private Observable<RxBleScanResult> createScanOperationApi18(@Nullable final UUID[] filterServiceUUIDs) {
        final Set<UUID> filteredUUIDs = uuidUtil.toDistinctSet(filterServiceUUIDs);
        final RxBleRadioOperationScanLegacy
                scanOperation = new RxBleRadioOperationScanLegacy(filterServiceUUIDs, rxBleAdapterWrapper, uuidUtil);
        return rxBleRadio.queue(scanOperation)
                .doOnUnsubscribe(new Action0() {
                    @Override
                    public void call() {

                        synchronized (queuedScanOperations) {
                            scanOperation.stop();
                            queuedScanOperations.remove(filteredUUIDs);
                        }
                    }
                })
                .mergeWith(this.<RxBleInternalScanResultLegacy>bluetoothAdapterOffExceptionObservable())
                .map(new Func1<RxBleInternalScanResultLegacy, RxBleScanResult>() {
                    @Override
                    public RxBleScanResult call(RxBleInternalScanResultLegacy scanResult) {
                        return convertToPublicScanResult(scanResult);
                    }
                })
                .share();
    }

    private void guardBluetoothAdapterAvailable() {
        if (!rxBleAdapterWrapper.hasBluetoothAdapter()) {
            throw new UnsupportedOperationException("RxAndroidBle library needs a BluetoothAdapter to be available in the system to work."
            + " If this is a test on an emulator then you can use 'https://github.com/Polidea/RxAndroidBle/tree/master/mockrxandroidble'");
        }
    }
}
