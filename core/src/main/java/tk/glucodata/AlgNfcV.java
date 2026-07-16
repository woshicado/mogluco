/*      Modified 2026 by Joshua Meyer as part of Mogluco, a Material 3               */
/*      redesign fork of Juggluco. See the NOTICE file for details.                  */
/*                                                                                   */
/*      This file is part of Juggluco, an Android app to receive and display         */
/*      glucose values from Freestyle Libre 2 and 3 sensors.                         */
/*                                                                                   */
/*      Copyright (C) 2021 Jaap Korthals Altes <jaapkorthalsaltes@gmail.com>         */
/*                                                                                   */
/*      Juggluco is free software: you can redistribute it and/or modify             */
/*      it under the terms of the GNU General Public License as published            */
/*      by the Free Software Foundation, either version 3 of the License, or         */
/*      (at your option) any later version.                                          */
/*                                                                                   */
/*      Juggluco is distributed in the hope that it will be useful, but              */
/*      WITHOUT ANY WARRANTY; without even the implied warranty of                   */
/*      MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.                         */
/*      See the GNU General Public License for more details.                         */
/*                                                                                   */
/*      You should have received a copy of the GNU General Public License            */
/*      along with Juggluco. If not, see <https://www.gnu.org/licenses/>.            */
/*                                                                                   */
/*      Fri Jan 27 15:31:05 CET 2023                                                 */

package tk.glucodata;

import static tk.glucodata.Log.doLog;

import android.nfc.Tag;
import android.nfc.tech.NfcV;

/**
 * Low-level ISO-15693 (NfcV) command transceiver for FreeStyle sensors. This is the
 * Libre 3 subset of the legacy AlgNfcV — only {@link #wholenfccmd} (and its helpers) are
 * needed by the Libre 3 activation handshake ({@link Libre3} / {@code tk.glucodata.libre3.NFC}).
 * The Libre 2 activation/streaming methods (which depended on {@code Gen2}) are intentionally
 * omitted, since the redesign targets Libre 3 only.
 */
public class AlgNfcV {
  private static final String LOG_ID = "AlgNfcV";
  private static final long nfcReadTimeout = 5000; // [ms]

  public static boolean goodnfc(final byte[] data) {
    return data != null && data.length > 0 && (data[0] & 1) == 0;
  }

  public static byte[] wholenfccmd(final Tag tag, final byte[] cmd) {
    return wholenfccmdtimes(tag, cmd, 10);
  }

  private static byte[] wholenfccmdtimes(final Tag tag, final byte[] cmd, int times) {
    if (doLog) {
      Log.i(LOG_ID, "wholenfccmdtimes " + times);
    }
    for (int outi = 0; outi < times; outi++) {
      final NfcV nfcvTag = NfcV.get(tag);
      if (nfcvTag == null) {
        Log.e(LOG_ID, "NfcV.get(tag)==null");
        return null;
      }
      try {
        nfcvTag.connect();
        final long endReadingTime = System.currentTimeMillis() + nfcReadTimeout;
        byte[] infodata = null;
        int it = times;
        do {
          try {
            infodata = nfcvTag.transceive(cmd);
          } catch (Throwable error) {
            String mess = error != null ? error.toString() : null;
            if (mess == null) {
              mess = "error";
            }
            if (doLog) {
              Log.i(LOG_ID, "transceive " + mess);
            }
            if ((System.currentTimeMillis() > endReadingTime)) {
              if (doLog) {
                Log.i(LOG_ID, "tag read timeout " + System.currentTimeMillis());
              }
              return null;
            }
          }
          if (goodnfc(infodata)) {
            return infodata;
          }
        } while (--it != 0);
        if (doLog) {
          Log.i(LOG_ID, "tried " + times + " times");
        }
        return null;
      } catch (Exception e) {
        if (doLog) {
          Log.i(LOG_ID, "connect: " + e.toString());
        }
      } finally {
        try {
          if (doLog) {
            Log.i(LOG_ID, "nfcvTag.close()");
          }
          nfcvTag.close();
        } catch (Exception e) {
          Log.stack(LOG_ID, "Error closing tag ", e);
        }
      }
    }
    return null;
  }
}
