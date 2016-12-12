/**
 * Wire
 * Copyright (C) 2016 Wire Swiss GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.waz.zclient.core.api.scala;

import com.waz.api.ConnectionIndicator;
import com.waz.api.NetworkMode;
import com.waz.api.ZMessagingApi;
import com.waz.zclient.core.stores.network.INetworkStore;
import com.waz.zclient.core.stores.network.NetworkAction;
import com.waz.zclient.core.stores.network.NetworkStoreObserver;
import timber.log.Timber;

import java.util.HashSet;
import java.util.Set;

public class ScalaNetworkStore implements INetworkStore {

    private ConnectionIndicator connectionIndicator;
    private Set<NetworkStoreObserver> networkStoreObservers = new HashSet<>();
    private NetworkMode networkMode = NetworkMode.OFFLINE;
    private boolean isServerError = false;

    final private ModelObserver<ConnectionIndicator> connectionIndicatorModelObserver = new ModelObserver<ConnectionIndicator>() {
        @Override
        public void updated(ConnectionIndicator model) {
            Timber.i("NETWORK_TRACE: ConnectionIndicator updated, net=%s, error=%b, websocket=%b", model.getNetworkMode().toString(), model.isConnectionError(), model.isWebSocketConnected());
            networkMode = model.getNetworkMode();
            isServerError = hasInternetConnection() && model.isConnectionError(); //only care about server errors when there is an internet connection
            notifyConnectivityChange();
        }
    };

    public ScalaNetworkStore(ZMessagingApi zMessagingApi) {
        Timber.i("NETWORK_TRACE: ScalaNetworkStore created, %s", this.toString());
        connectionIndicator = zMessagingApi.getConnectionIndicator();
        connectionIndicatorModelObserver.setAndUpdate(connectionIndicator);
    }

    @Override
    public void addNetworkStoreObserver(NetworkStoreObserver networkStatusObserver) {
        networkStoreObservers.add(networkStatusObserver);
        networkStatusObserver.onConnectivityChange(hasInternetConnection(), isServerError);
    }

    @Override
    public void removeNetworkStoreObserver(NetworkStoreObserver networkStatusObserver) {
        networkStoreObservers.remove(networkStatusObserver);
    }

    @Override
    public void tearDown() {
        connectionIndicator = null;
    }

    @Override
    public boolean hasInternetConnection() {
        Timber.i("NETWORK_TRACE: hasInternetConnection=%b", networkMode != NetworkMode.OFFLINE);
        return networkMode != NetworkMode.OFFLINE;
    }

    @Override
    public boolean hasWifiConnection() {
        Timber.i("NETWORK_TRACE: hasWifiConnection=%b", networkMode == NetworkMode.WIFI);
        return networkMode == NetworkMode.WIFI;
    }

    /**
     * We want to notify the user if there is a server connection error OR no internet connection, but we want to try
     * and perform the action if there is internet but no server connection.
     */
    @Override
    public void doIfHasInternetOrNotifyUser(NetworkAction networkAction) {
        notifyIfNoInternet();
        if (networkAction == null) {
            return;
        }

        if (hasInternetConnection()) {
            networkAction.execute(networkMode);
        } else {
            networkAction.onNoNetwork();
        }
    }

    private void notifyIfNoInternet() {
        if (hasInternetConnection() && !isServerError) {
            return;
        }

        for (NetworkStoreObserver notifier : networkStoreObservers) {
            notifier.onNoInternetConnection(isServerError);
        }
    }

    private void notifyConnectivityChange() {
        for (NetworkStoreObserver networkStoreObserver : networkStoreObservers) {
            networkStoreObserver.onConnectivityChange(hasInternetConnection(), isServerError);
        }
    }
}
