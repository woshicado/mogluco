/*      Modified 2026 by Joshua Meyer as part of Mogluco, a Material 3               */
/*      redesign fork of Juggluco. See the NOTICE file for details.                  */
/*                                                                                   */
/*      Watch-side WiFi binder, ported from the legacy wear UseWifi.java (GPLv3,        */
/*      Copyright (C) 2021 Jaap Korthals Altes). Requests a WiFi network and binds      */
/*      the process to it so the initial bulk mirror sync can run over TCP; falls       */
/*      back to the Data-Layer tunnel otherwise. The legacy open-WiFi-settings          */
/*      fallback is dropped (no legacy MainActivity here) — it just retries.            */

package tk.glucodata;

import static tk.glucodata.Log.doLog;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;

class UseWifi {
    private static UseWifi thisone = null;
    private static final String LOG_ID = "UseWifi";
    private final ConnectivityManager connectivityManager =
        (ConnectivityManager) Applic.app.getSystemService(Context.CONNECTIVITY_SERVICE);

    private void disusewifi() {
        if (doLog) Log.i(LOG_ID, "disusewifi");
        try {
            connectivityManager.bindProcessToNetwork(null);
            connectivityManager.unregisterNetworkCallback(WiFiCallback);
        } catch (Throwable th) {
            Log.stack(LOG_ID, "disusewifi", th);
        }
    }

    private final ConnectivityManager.NetworkCallback WiFiCallback =
        new ConnectivityManager.NetworkCallback() {
            public void onAvailable(Network network) {
                super.onAvailable(network);
                Natives.resetnetwork();
                if (doLog) Log.i(LOG_ID, "WiFiCallback onAvailable");
                connectivityManager.bindProcessToNetwork(network);
                Applic.wakemirrors();
            }

            public void onLost(Network network) {
                super.onLost(network);
                if (doLog) Log.i(LOG_ID, "WiFiCallback onLost");
            }
        };

    private void startusewifi() {
        if (doLog) Log.i(LOG_ID, "usewifi");
        try {
            connectivityManager.requestNetwork(
                new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build(),
                WiFiCallback);
        } catch (Throwable th) {
            Log.stack(LOG_ID, "startusewifi", th);
        }
    }

    static synchronized void usewifi() {
        if (thisone == null) {
            thisone = new UseWifi();
            thisone.startusewifi();
        }
    }

    static synchronized void stopusewifi() {
        if (thisone != null) {
            thisone.disusewifi();
            thisone = null;
        }
    }
}
