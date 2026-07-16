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

/*
 * Phase 0 load-time stubs.
 *
 * libg.so's JNI_OnLoad resolves a fixed set of glue classes and method IDs the moment
 * the library is loaded. If any class or method is missing it leaves a *pending JNI
 * exception*, which CheckJNI (enabled for debuggable apps) turns into a hard abort on the
 * next JNI call. So these classes + the exact signatures below must exist for
 * System.loadLibrary("g") to succeed at all.
 *
 * These are intentionally minimal placeholders that only satisfy the loader. In Phase 1
 * they are replaced by the real engine glue (the functional GlucoseCurve renderer, the
 * Applic engine callbacks, the Libreview/MessageSender bridges) as the native-backed data
 * path is built out. The method bodies here are never exercised by the Phase 0 link probe,
 * which only calls Natives.openglversion().
 *
 * The signatures are dictated by:
 *   - curve/javacurve.cpp  JNI_OnLoad  (GlucoseCurve, Applic)
 *   - net/libreview/libreview.cpp      (Libreview)
 *   - watch/messages.cpp   jinitmessages (MessageSender)
 */
final class JniLoadStubs {
    private JniLoadStubs() {}
}
