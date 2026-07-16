/*      This file is part of Mogluco, a Material 3 redesign fork of                  */
/*      Juggluco, for Freestyle Libre 2 and 3 glucose sensors.                       */
/*                                                                                   */
/*      Copyright (C) 2026 Joshua Meyer                                              */
/*      Portions derived from Juggluco, Copyright (C) 2021                           */
/*      Jaap Korthals Altes <jaapkorthalsaltes@gmail.com>.                           */
/*                                                                                   */
/*      Mogluco is free software: you can redistribute it and/or modify              */
/*      it under the terms of the GNU General Public License as published            */
/*      by the Free Software Foundation, either version 3 of the License, or         */
/*      (at your option) any later version.                                          */
/*                                                                                   */
/*      Mogluco is distributed in the hope that it will be useful, but               */
/*      WITHOUT ANY WARRANTY; without even the implied warranty of                   */
/*      MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.                         */
/*      See the GNU General Public License for more details.                         */
/*                                                                                   */
/*      You should have received a copy of the GNU General Public License            */
/*      along with Mogluco. If not, see <https://www.gnu.org/licenses/>.             */
/*                                                                                   */
/*      LibreView HTTP bridge. The native libreview thread (net/libreview/libreview.cpp)      */
/*      builds the JSON payloads and calls these static methods back via JNI to perform the    */
/*      actual HTTP. Ported from the legacy mobile tk.glucodata.Libreview, headless (no UI,     */
/*      no ProviderInstaller — minSdk 26 ships modern TLS).                                     */

package tk.glucodata;

import static java.net.HttpURLConnection.HTTP_OK;
import static tk.glucodata.Log.doLog;

import android.os.Build;
import androidx.annotation.Keep;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.json.JSONObject;

/** Resolved by libg.so initlibreviewjni (net/libreview/libreview.cpp). */
class Libreview {
    private static final String LOG_ID = "Libreview";

    static final String nothing = "";
    static final String success = "success";

    /** Human-readable status of the last LibreView operation (polled by the UI). */
    static volatile String librestatus = nothing;

    /** Timestamp string of the last successful upload, or null. */
    static volatile String posttime = null;

    private static String termsofuseversionurl = null;
    private static final String libre3start =
        "https://fsll3.freestyleserver.com/Payloads/Mobile/FSLibre3/Android/Assets/3.3.0/DE.json";

    private static String datestr(long ms) {
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new java.util.Date(ms));
    }

    private static String stackline(Throwable th) {
        return th == null ? "error" : (th.getMessage() == null ? th.toString() : th.getMessage());
    }

    /**
     * Opens a connection with the timeouts every LibreView request uses: Abbott's servers
     * occasionally hang, and without limits the native libreview thread blocks forever.
     */
    private static HttpURLConnection connect(URL url) throws Exception {
        HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
        urlConnection.setConnectTimeout(10000);
        urlConnection.setReadTimeout(60000);
        return urlConnection;
    }

    /**
     * Drains the error body when present (so the pooled socket can be reused) and releases the
     * connection. Every request path calls this in a finally.
     */
    private static void finishconnection(HttpURLConnection urlConnection) {
        try (InputStream err = urlConnection.getErrorStream()) {
            if (err != null) {
                byte[] buf = new byte[4096];
                while (err.read(buf) > 0) {
                }
            }
        } catch (Throwable ignore) {
        } finally {
            urlConnection.disconnect();
        }
    }

    /** Reads the response body as JSON; the caller owns the connection and disconnects it. */
    private static JSONObject readJSONObject(HttpURLConnection urlConnection) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(urlConnection.getInputStream(), StandardCharsets.UTF_8))) {
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) > 0) {
                sb.append(buf, 0, n);
            }
        }
        return new JSONObject(sb.toString());
    }

    static String getlibregateway(boolean libre3) {
        return libre3 ? "FSLibreLink3.Android" : "FSLibreLink.Android";
    }

    @Keep
    static boolean putsensor(boolean libre3, byte[] textbytes) {
        if (librestatus.equals(nothing) || librestatus.equals(success)) {
            librestatus = datestr(System.currentTimeMillis()) + " start putsensor";
        }
        try {
            for (int i = 0; i < 3; i++) {
                final String baseurl = Natives.getlibrebaseurl(libre3);
                URL url = new URL(baseurl + "/api/nisperson");
                HttpURLConnection urlConnection = connect(url);
                try {
                    urlConnection.setDoOutput(true);
                    urlConnection.setRequestMethod("PUT");
                    urlConnection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                    urlConnection.setRequestProperty("Content-Length", Integer.toString(textbytes.length));
                    try (OutputStream out = new BufferedOutputStream(urlConnection.getOutputStream())) {
                        out.write(textbytes);
                        out.flush();
                    }
                    JSONObject object = readJSONObject(urlConnection);
                    final int status = object.getInt("status");
                    if (status != 0) {
                        String reason = object.optString("reason", "");
                        if (status == 20 && "wrongDeviceInToken".equals(reason)) {
                            switch (i) {
                                case 0:
                                    if (!postgetauth(libre3)) {
                                        if (!libreconfig(libre3, false)) return false;
                                        i = 1;
                                    }
                                    break;
                                case 1:
                                    if (!libreconfig(libre3, false)) return false;
                                    break;
                                default:
                                    librestatus = "putsensor reason=" + reason;
                                    return false;
                            }
                            continue;
                        }
                        librestatus = "putsensor: status=" + status
                            + (reason.isEmpty() ? "" : (" reason=" + reason));
                    }
                    return status == 0;
                } finally {
                    finishconnection(urlConnection);
                }
            }
            return false;
        } catch (Throwable th) {
            librestatus = "putsensor " + stackline(th);
            Log.e(LOG_ID, librestatus);
            return false;
        }
    }

    static boolean postgetauth(boolean libre3) {
        String gateway = getlibregateway(libre3);
        String one = Natives.getlibreDeviceID(libre3);
        String password = Natives.getlibrepass();
        String login = Natives.getlibreemail();
        Locale loc = Locale.getDefault();
        String language = loc.getLanguage() + '-' + loc.getCountry();
        String culture = language;
        boolean setdevice = false;
        // At most two attempts: one with SetDevice=false and — when Abbott answers
        // status=20/wrongDeviceForUser — one with SetDevice=true. A second refusal would
        // otherwise retry forever.
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                final String baseurl = Natives.getlibrebaseurl(libre3);
                URL url = new URL(baseurl + "/api/nisperson/getauthentication");
                HttpURLConnection urlConnection = connect(url);
                try {
                    urlConnection.setRequestMethod("POST");
                    urlConnection.setDoOutput(true);
                    if (libre3) {
                        urlConnection.setRequestProperty("Content-Type", "application/json");
                        urlConnection.setRequestProperty("Platform", "Android");
                        urlConnection.setRequestProperty("Version", "3.3.0");
                        urlConnection.setRequestProperty("Abbott-ADC-App-Platform",
                            "Android/" + Build.VERSION.RELEASE + "/FSL3/3.3.0.9092");
                        urlConnection.setRequestProperty("Accept-Language", language);
                        urlConnection.setRequestProperty("x-api-key", Natives.getnewYuApiKey(libre3));
                        urlConnection.setRequestProperty("x-newyu-token", "");
                    } else {
                        urlConnection.setRequestProperty("Abbott-ADC-App-Platform",
                            "Android/" + Build.VERSION.RELEASE + "/FSLL/2.10.1.10406");
                        urlConnection.setRequestProperty("Accept-Language",
                            language + ", " + loc.getLanguage() + ";q=0.8");
                        urlConnection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    }
                    // JSONObject escapes the values: hand-concatenated JSON produced an invalid
                    // payload for credentials containing '"' or '\'. Field names and nesting are
                    // unchanged.
                    JSONObject auth = new JSONObject();
                    auth.put("Culture", culture);
                    auth.put("DeviceId", one);
                    auth.put("Password", password);
                    auth.put("SetDevice", setdevice);
                    auth.put("UserName", login);
                    auth.put("Domain", "Libreview");
                    auth.put("GatewayType", gateway);
                    byte[] textbytes = auth.toString().getBytes(StandardCharsets.UTF_8);
                    urlConnection.setRequestProperty("Content-Length", Integer.toString(textbytes.length));
                    try (OutputStream out = new BufferedOutputStream(urlConnection.getOutputStream())) {
                        out.write(textbytes);
                        out.flush();
                    }
                    final int code = urlConnection.getResponseCode();
                    if (code == HTTP_OK) {
                        JSONObject object = readJSONObject(urlConnection);
                        int status = object.getInt("status");
                        if (status != 0) {
                            String reason = object.optString("reason", "");
                            Log.e(LOG_ID, "postgetauth: status=" + status + " reason=" + reason);
                            if (status == 20 && "wrongDeviceForUser".equals(reason)) {
                                setdevice = true;
                                continue;
                            }
                            librestatus = "postgetauth: status=" + status + " reason=" + reason;
                            return false;
                        }
                        JSONObject result = object.getJSONObject("result");
                        String usertoken = result.getString("UserToken");
                        Natives.setlibreUserToken(libre3, usertoken);
                        String accountid = result.getString("AccountId");
                        Natives.setlibreAccountID(accountid);
                        librestatus = "Received AccountID";
                        if (libre3) {
                            int dat = 0;
                            try {
                                dat = Integer.parseInt(result.optString("DateOfBirth", "0"));
                            } catch (NumberFormatException ignore) {
                                // a non-numeric DOB shouldn't fail the whole auth
                            }
                            Natives.savelibrerubbish(
                                result.optString("FirstName", ""), result.optString("LastName", ""), dat,
                                result.optString("GuardianFirstName", ""), result.optString("GuardianLastName", ""));
                            gettermversion(result.optString("UiLanguage", language));
                        }
                        return true;
                    }
                    librestatus = "postgetauth: getResponseCode()=" + code;
                    return false;
                } finally {
                    finishconnection(urlConnection);
                }
            } catch (Throwable th) {
                librestatus = "postgetauth:\t" + stackline(th);
                Log.e(LOG_ID, librestatus);
                return false;
            }
        }
        return false;
    }

    @Keep
    static boolean postmeasurements(boolean libre3, byte[] measurementdata) {
        String nowstr = datestr(System.currentTimeMillis());
        if (librestatus.equals(nothing) || librestatus.equals(success)) {
            librestatus = nowstr + " start posting";
        }
        try {
            for (int i = 0; i < 3; i++) {
                final String baseurl = Natives.getlibrebaseurl(libre3);
                URL url = new URL(baseurl + "/api/measurements");
                HttpURLConnection urlConnection = connect(url);
                try {
                    urlConnection.setRequestMethod("POST");
                    urlConnection.setDoOutput(true);
                    urlConnection.setRequestProperty("Content-Type", "application/json");
                    urlConnection.setRequestProperty("Content-Length", Integer.toString(measurementdata.length));
                    if (libre3) {
                        urlConnection.setRequestProperty("Platform", "Android");
                        urlConnection.setRequestProperty("Version", "3.3.0");
                        urlConnection.setRequestProperty("Abbott-ADC-App-Platform",
                            "Android/" + Build.VERSION.RELEASE + "/FSL3/3.3.0.9092");
                        Locale loc = Locale.getDefault();
                        urlConnection.setRequestProperty("Accept-Language",
                            loc.getLanguage() + '-' + loc.getCountry());
                        urlConnection.setRequestProperty("x-api-key", Natives.getnewYuApiKey(libre3));
                        urlConnection.setRequestProperty("x-newyu-token", Natives.getlibreUserToken(libre3));
                    }
                    try (OutputStream out = new BufferedOutputStream(urlConnection.getOutputStream())) {
                        out.write(measurementdata);
                        out.flush();
                    }
                    final int code = urlConnection.getResponseCode();
                    if (code == HTTP_OK) {
                        JSONObject object = readJSONObject(urlConnection);
                        int status = object.getInt("status");
                        if (status != 0) {
                            String reason = object.optString("reason", "");
                            Log.e(LOG_ID, "postmeasurements status=" + status + " reason=" + reason);
                            if (status == 20 && "wrongDeviceInToken".equals(reason)) {
                                switch (i) {
                                    case 0:
                                        if (!postgetauth(libre3)) {
                                            if (!libreconfig(libre3, false)) return false;
                                            i = 1;
                                        }
                                        break;
                                    case 1:
                                        if (!libreconfig(libre3, false)) return false;
                                        break;
                                    default:
                                        librestatus = "postmeasurements status=" + status + " reason=" + reason;
                                        return false;
                                }
                                continue;
                            }
                            librestatus = "postmeasurements status=" + status + " reason=" + reason;
                            return false;
                        }
                        posttime = nowstr;
                        librestatus = success;
                        return true;
                    }
                    librestatus = "postmeasurements ResponseCode=" + code;
                    return false;
                } finally {
                    finishconnection(urlConnection);
                }
            }
            return false;
        } catch (Throwable th) {
            librestatus = "postmeasurements\n" + stackline(th);
            Log.e(LOG_ID, librestatus);
            return false;
        }
    }

    private static boolean gettermversion(String lang) {
        try {
            if (termsofuseversionurl == null) {
                return false;
            }
            URL url = new URL(termsofuseversionurl.replace("<locale>", lang));
            HttpURLConnection urlConnection = connect(url);
            try {
                urlConnection.setRequestMethod("GET");
                final int code = urlConnection.getResponseCode();
                return code == HTTP_OK;
            } finally {
                finishconnection(urlConnection);
            }
        } catch (Throwable th) {
            Log.e(LOG_ID, "gettermversion " + stackline(th));
            return false;
        }
    }

    private static String libre3getconfigURL() {
        try {
            URL url = new URL(libre3start);
            HttpURLConnection urlConnection = connect(url);
            try {
                urlConnection.setRequestMethod("GET");
                final int code = urlConnection.getResponseCode();
                if (code == HTTP_OK) {
                    JSONObject object = readJSONObject(urlConnection);
                    final String conurl = object.getString("Configuration");
                    termsofuseversionurl = object.optString("TermsOfUseVersion", null);
                    return conurl;
                }
                librestatus = "libre3getconfigURL failed code=" + code;
                return null;
            } finally {
                finishconnection(urlConnection);
            }
        } catch (Throwable th) {
            librestatus = "libre3getconfigURL:\n" + stackline(th);
            Log.e(LOG_ID, librestatus);
            return null;
        }
    }

    private static final String[] urlnames = {
        "https://fsll.freestyleserver.com/Payloads/Mobile/FSLibreLink/Android/Config/FSLibreLink_Android_2.10_GB_config.json",
        "https://fsll.freestyleserver.com/Payloads/Mobile/FSLibreLink/Android/Config/FSLibreLink_Android_2.10_FR_config.json",
        "https://fsll.freestyleserver.com/Payloads/Mobile/FSLibreLink/Android/Config/FSLibreLink_Android_2.10_NL_config.json",
        "https://fsll.freestyleserver.com/Payloads/Mobile/FSLibreLink/Android/Config/FSLibreLink_Android_2.10_PL_config.json",
        "https://fsll.freestyleserver.com/Payloads/Mobile/FSLibreLink/Android/Config/FSLibreLink_Android_2.10_RU_config.json",
    };

    @Keep
    public static boolean libreconfig(boolean libre3, boolean restart) {
        if (restart || librestatus.equals(nothing) || librestatus.equals(success)) {
            librestatus = datestr(System.currentTimeMillis()) + " libreconfig";
        }
        String urlstring;
        if (libre3) {
            urlstring = libre3getconfigURL();
            if (urlstring == null) {
                return false;
            }
        } else {
            int country = Natives.getLibreCountry();
            urlstring = urlnames[(country >= 0 && country < urlnames.length) ? country : 0];
        }
        try {
            URL url = new URL(urlstring);
            HttpURLConnection urlConnection = connect(url);
            try {
                urlConnection.setRequestMethod("GET");
                final int code = urlConnection.getResponseCode();
                if (code != HTTP_OK) {
                    librestatus = "libreconfig getResponseCode()=" + code;
                    Log.e(LOG_ID, librestatus);
                    return false;
                }
                JSONObject object = readJSONObject(urlConnection);
                Natives.setlibrebaseurl(libre3, object.getString("newYuUrl"));
                Object jobj = object.opt("newYuApiKey");
                if (jobj != null) {
                    Natives.setnewYuApiKey(libre3, jobj instanceof String ? (String) jobj : String.valueOf(jobj));
                }
            } finally {
                finishconnection(urlConnection);
            }
            return postgetauth(libre3);
        } catch (Throwable th) {
            librestatus = "libreconfig:\n" + stackline(th);
            Log.e(LOG_ID, librestatus);
            return false;
        }
    }

    // Retained for native compatibility (legacy single-arg overload, unused by Libre 3 path).
    static boolean putsensor(String serial) {
        return false;
    }
}
