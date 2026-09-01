/*      This file is part of Mogluco, a Material 3 redesign fork of                  */
/*      Juggluco, for Freestyle Libre 2 and 3 glucose sensors.                       */
/*                                                                                   */
/*      Copyright (C) 2026 Joshua Meyer                                              */
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
/*      This file is part of Juggluco, an Android app to receive and display         */
/*      glucose values from Freestyle Libre 2 and 3 sensors.                         */
/*      Licensed under the GNU General Public License v3 or later.                   */

package tk.glucodata;

/**
 * JNI bridge to the native engine (libg.so).
 *
 * <p><b>Phase 0 (link proof):</b> this is a minimal stand-in declaring just
 * {@link #openglversion()} — a constant-returning native method that needs no engine
 * initialization — to prove the new Compose app can load libg.so and resolve a
 * {@code Java_tk_glucodata_Natives_*} symbol. The native library binds statically by
 * symbol name (its {@code RegisterNatives} path is disabled) and its {@code JNI_OnLoad}
 * degrades gracefully when UI classes are absent, so this minimal class loads cleanly.
 *
 * <p><b>Phase 1:</b> this grows into the full ~694-method surface and the engine glue
 * classes it calls back into (Applic, GlucoseCurve, …) move into :core alongside it.
 */
public class Natives {
  private static final String LOG_ID = "Natives";

  static {
    try {
      System.loadLibrary("g");
      android.util.Log.d(LOG_ID, "libg.so loaded");
    } catch (Throwable error) {
      android.util.Log.e(LOG_ID, "loadLibrary(g) failed", error);
    }
  }

  /** Returns the OpenGL ES major version the native renderer targets (constant). */
  public static native int openglversion();

  // --- Engine lifecycle (bootstrap order mirrors tk.glucodata.nums.numio) ---

  /** Sets the data directory + native lib dir; returns 0 on success, 1..13 on error. */
  public static native int setfilesdir(String dir, String country, String nativedir);

  /** Sets the engine locale (language code). */
  public static native void setlocale(String loc);

  /** Starts the sensor I/O subsystem. */
  public static native void startsensors();

  /** Starts the meals subsystem. */
  public static native void startmeals();

  /** Starts the engine worker threads (must come after setfilesdir). */
  public static native void startthreads();

  // --- Reads ---

  /** Most recent glucose reading as a formatted struct, or null if none yet. */
  public static native strGlucose lastglucose();

  /** Timestamp (ms) of the most recent reading, or 0 if none. */
  public static native long lastglucosetime();

  // --- Nums (events: carbs / insulin / notes) ---

  /** Opens a nums store and returns its native pointer (bootstrap, after startthreads). */
  public static native long openNums(String base, long ident);

  /** Allocates the "new hit" sentinel pointer (bootstrap). */
  public static native long newhit();

  /** Saves an event of {@code type} (a label index) with {@code value} at {@code time} (sec). */
  public static native void saveNum(long ptr, long time, float value, int type, int mealptr);

  /** The event-type labels (carbs / insulin / note / …); the index is the event {@code type}. */
  public static native java.util.ArrayList<String> getLabels();

  /** The label index that represents carbohydrates / meals. */
  public static native byte getmealvar();

  /** Number of stored events in the given nums store. */
  public static native int getlastNum(long ptr);

  /** The event at {@code pos} in the given nums store. */
  public static native tk.glucodata.nums.item getNumitem(long ptr, int pos);

  /** Removes the event at {@code pos} from the given nums store (shifts later positions down). */
  public static native void removeNum(long ptr, int pos);

  // --- Units / target range ---

  /** Display unit code (see Engine.unitIsMmol). */
  public static native int getunit();

  public static native void setunit(int unit);

  /** Target range bounds, in the engine's stored unit. */
  public static native float targetlow();

  public static native float targethigh();

  public static native void setTargetRange(float tlow, float thigh);

  // --- Alarms ---

  public static native void setalarms(
      float alow, float ahigh,
      boolean lowalarm, boolean highalarm, boolean availablealarm, boolean lossalarm);

  public static native float alarmlow();

  public static native float alarmhigh();

  public static native boolean hasalarmlow();

  public static native boolean hasalarmhigh();

  public static native boolean hasalarmloss();

  public static native boolean hasvaluealarm();

  // --- Export ---

  /**
   * Writes export data to an open file descriptor. {@code type}: 0=events, 1=scans,
   * 2=stream, 3=glucose history, 4=meals, 5=LibreView. The exported window is the
   * engine's current graph window narrowed to {@code days} (see Engine.exportTo).
   */
  public static native boolean exportdata(int type, int fd, float days);

  // --- Logging (used by tk.glucodata.Log) ---

  public static native void log(String mess);

  public static native void showbytes(String mess, byte[] ar);

  // --- Libre 3 NFC activation ---

  public static native int nfcdata(byte[] uid, byte[] info, byte[] dat);

  public static native void enabledStreaming(byte[] uid, byte[] info, int val, byte[] address);

  public static native boolean hasBluetooth(byte[] sensorident, byte[] patchinfo);

  public static native byte activationcommand(byte[] info);

  public static native byte[] activationpayload(byte[] id, byte[] info, byte person);

  public static native byte[] bluetoothOnKey(byte[] sensorident, byte[] patchinfo);

  public static native void bluetoothback(byte[] sensorident, byte[] info);

  public static native String getserial(byte[] uid, byte[] info);

  public static native int startTimeIDsum(byte[] uit, long time, long accountID);

  public static native long interpret3NFC2(byte[] nfc1, byte[] b, long time);

  public static native long getLibre3secs(byte[] nfc1ar);

  // --- Libre 3 BLE crypto ---

  public static native long initcrypt(long prev, byte[] key, byte[] iv);

  public static native void endcrypt(long jptr);

  public static native byte[] intEncrypt(long cryptptr, int kind, byte[] plain);

  public static native byte[] intDecrypt(long cryptptr, int kind, byte[] encrypted);

  public static native int processint(int i2, byte[] bArr, byte[] bArr2);

  public static native byte[] processbar(int i2, byte[] bArr, byte[] bArr2);

  public static native float thresholdchange(float drate);

  // --- Libre 3 data save / patch status ---

  public static native long saveLibre3MinuteL(long sensorptr, byte[] mindata);

  public static native boolean saveLibre3fastData(long sensorptr, byte[] mindata);

  public static native boolean saveLibre3History(long sensorptr, byte[] mindata);

  public static native int libre3EventLog(long sensorptr, byte[] logs);

  public static native void setLibre3kAuth(long sensorptr, byte[] kauthin);

  public static native byte[] getLibre3kAuth(long sensorptr);

  public static native int libre3processpatchstatus(long sensorptr, byte[] status);

  public static native byte[] getpin(long sensorptr);

  public static native int getlastLifeCountReceived(long sensorptr);

  public static native int getlastHistoricLifeCountReceived(long sensorptr);

  public static native byte[] libre3ControlHistory(int arg, int from);

  public static native byte[] libre3ClinicalControl(int arg, int from);

  public static native byte[] libre3EventLogControl(int arg);

  // --- Sensor / dataptr management ---

  public static native long getdataptr(String sensorname);

  public static native long getsensorptr(long dataptr);

  public static native boolean sameSensor(long one, long two);

  public static native void freedataptr(long dataptr);

  public static native void setDeviceAddress(long dataptr, String deviceAddress);

  public static native String getDeviceAddress(long dataptr, boolean getnew);

  public static native String getsensortext(long dataptr);

  public static native String getSensorName(long dataptr);

  /**
   * Current sensor state for the error banner: -1 invalid, 0 ok, 6 sensor error (defective),
   * 7 replace sensor, 8 not ready (warm-up), 9 ended. Takes a data pointer ({@link #getdataptr}).
   */
  public static native int getSensorState(long dataptr);

  public static native long getSensorStartmsec(long dataptr);

  public static native int getLibreVersion(long dataptr);

  public static native int getsensorgen(long dataptr);

  public static native boolean askstreamingEnabled(long dataptr);

  public static native String[] activeSensors();

  /** Opaque pointers to each active sensor's stored glucose data (SensorGlucoseData*). */
  public static native long[] activeSensorPtrs();

  /**
   * Opaque pointers to <em>every</em> stored sensor's glucose data (SensorGlucoseData*), including
   * past/finished sensors — unlike {@link #activeSensorPtrs}, which is limited to recently-active
   * ones. Read-only; used to show the full historical glucose across all sensors.
   */
  public static native long[] allSensorPtrs();

  /** Sensor serial/name from a sensor-data pointer (from {@link #activeSensorPtrs}). */
  public static native String namefromSensorptr(long sensorptr);

  /**
   * One stored 5-minute stream reading for a sensor, starting search at {@code pos}. Returns a
   * packed long: bits[0..31] = time (epoch SECONDS), bits[32..47] = glucose (mg/dL), bits[48..63]
   * = the next position to read. When no further valid reading exists, the time bits are 0 and
   * the next-position bits hold the stream length. Iterate from pos=0 advancing to next-position.
   */
  public static native long streamfromSensorptr(long sensorptr, int pos);

  /**
   * Like {@link #streamfromSensorptr} but reads the stored HISTORIC (5-minute) array — the buffer
   * the Libre 3 backfills into on reconnect — instead of the live 1-minute poll stream. Same packed
   * return layout. Used to fill graph gaps the live stream missed while disconnected.
   */
  public static native long historyfromSensorptr(long sensorptr, int pos);

  public static native void setmaxsensors(int nr);

  public static native void updateUsedSensors();

  public static native boolean getusebluetooth();

  public static native boolean getpriority();

  public static native boolean getAndroid13();

  public static native void setAndroid13(boolean val);

  // --- Account ID (Libreview) + deactivation ---

  public static native long getlibreAccountIDnumber();

  public static native void setlibreAccountIDnumber(long num);

  public static native void setlibreAccountID(String AccountID);

  public static native long manualLibreAccountIDnumber();

  public static native void finishSensor(long dataptr);

  // --- LibreView (fetch account ID via email/password; upload data) ---
  // The HTTP itself runs in the native libreview thread, which calls back into
  // tk.glucodata.Libreview (libreconfig/postgetauth/postmeasurements/putsensor).

  public static native void setlibreemail(String email);

  public static native String getlibreemail();

  public static native void setlibrepass(String pass);

  public static native String getlibrepass();

  public static native int getLibreCountry();

  public static native void setLibreCountry(int val);

  public static native String getlibrebaseurl(boolean l3);

  public static native void setlibrebaseurl(boolean l3, String baseurl);

  public static native String getnewYuApiKey(boolean l3);

  public static native void setnewYuApiKey(boolean l3, String jpass);

  public static native String getlibreUserToken(boolean l3);

  public static native void setlibreUserToken(boolean l3, String UserToken);

  public static native String getlibreDeviceID(boolean l3);

  public static native String getlibreAccountID();

  public static native void savelibrerubbish(
      String FirstName, String LastName, int dat, String GardianFirstName, String GuardianLastName);

  /** "First Last" as stored from the LibreView account fetch, or "" when never fetched. */
  public static native String getPatientName();

  /** Triggers the native thread to fetch the account ID from LibreView (uses stored email/pass). */
  public static native void askServerforAccountID();

  /** Enable/disable uploading collected data to LibreView. */
  public static native void setuselibreview(boolean val);

  public static native boolean getuselibreview();

  /** Wake the upload thread to send now ({@code min}=0 = immediately). */
  public static native void wakelibreview(int min);

  /** Include logged amounts (insulin/carbs/notes) in LibreView uploads. */
  public static native void setSendNumbers(boolean val);

  public static native boolean getSendNumbers();

  /**
   * Classify event label {@code index} for LibreView upload: kind 0=off, 1=rapid insulin, 2=long
   * insulin, 3=carbs ({@code weight} = grams per logged unit), 4=custom note. {@code night}=0 is
   * the LibreView table ({@code night}=1 is Nightscout's).
   */
  public static native void setlibrenum(int night, int index, int kind, float weight);

  public static native int getlibrenumkind(int night, int index);

  // --- Calibration ---

  /** Apply user manual calibration corrections to displayed values (false = factory/raw). */
  public static native void setDoCalibrate(boolean val);

  public static native boolean getDoCalibrate();

  /** Also show the calibrated trace. */
  public static native void setshowcalibrated(boolean val);

  /** Apply calibration retroactively to readings before the calibration point. */
  public static native void setCalibratePast(boolean val);

  /** Fit the slope coefficient (false = offset-only calibration). */
  public static native void setCalibrateA(boolean val);

  /** The event-label index used for blood-glucose reference values (manual calibration). */
  public static native byte getbloodvar();

  public static native void setbloodvar(byte val);

  // --- Alarm thresholds / broadcast routing (read by the ported sensor classes) ---

  public static native boolean hasalarmveryhigh();

  public static native boolean hasalarmverylow();

  public static native short readalarmsuspension(int type);

  public static native void writealarmsuspension(int type, short sus);

  public static native boolean speakalarms();

  public static native boolean gethealthConnect();

  public static native boolean getJugglucobroadcast();

  public static native boolean geteverSensebroadcast();

  public static native boolean getlibrelinkused();

  public static native boolean getxbroadcast();

  // --- Phone ↔ watch mirror / sensor transfer (watch/netinfo.cpp, watch/messages.cpp) ---
  // Signatures copied verbatim from the legacy Natives.java — all are exported by libg.so.

  /** Build the transfer/handshake blob. watchhassensor: 1 = give the sensor to the peer
   *  watch, -1 = take it back to this phone, 0 = keep current roles. */
  public static native byte[] getmynetinfo(
      String name, boolean create, int watchhassensor, boolean galaxy, int setnums);

  /** Apply a received handshake blob; flips BLE ownership/stream roles. */
  public static native boolean setmynetinfo(String name, byte[] jar, boolean galaxy);

  /** Engine settings (unit, alarms, …) serialized for the watch bootstrap (/start). */
  public static native byte[] bytesettings();

  public static native boolean ontbytesettings(byte[] ar);

  /** 1 = the watch owns the sensor stream, 0 = it doesn't, -1 = no fresh watch data (>3 min). */
  public static native int directsensorwatch(String ident);

  public static native int hasWatchNums(String ident);

  /** Feed a Data-Layer /data message into the tunneled mirror stream. */
  public static native boolean message(byte[] data);

  public static native void setBlueMessage(String name, boolean val);

  /** Watch side: become the sensor's BLE owner and stream back (WEAROS builds only). */
  public static native void watchBluetooth(String name, boolean sensor, boolean watch);

  public static native void isGalaxyWatch(boolean val);

  public static native void setWearosdefaults(String jident, boolean galaxy);

  public static native boolean resetbylabel(String label, boolean galaxy);

  public static native void setusebluetooth(boolean val);

  public static native int backuphostNr();

  public static native void wakebackup();

  public static native void wakehereonly();

  public static native void wakestreamhereonly();

  public static native void resetnetwork();

  public static native void networkpresent();

  public static native void networkabsent();

  public static native boolean getWifi();

  public static native void setWifi(boolean val);

  /** Epoch millis of the last data received FROM the given watch node, or 0. Additive
   *  getter (this redesign) powering the transfer ack + auto-reclaim staleness. */
  public static native long lastwatchdata(String ident);

  private Natives() {}
}
