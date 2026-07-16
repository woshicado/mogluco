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
package de.mogluco.ui.util

import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.AutofillNode
import androidx.compose.ui.autofill.AutofillType
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalAutofill
import androidx.compose.ui.platform.LocalAutofillTree

/**
 * Exposes a text field to the Android Autofill framework so password managers (KeePass2Android,
 * Bitwarden, Google, …) can offer to fill it. Compose doesn't register fields for autofill
 * unless they declare their [autofillTypes]; this registers an [AutofillNode] for the field and
 * requests/cancels autofill as it gains/loses focus.
 *
 * Usage:
 * ```
 * OutlinedTextField(
 *     value = email, onValueChange = { email = it },
 *     modifier = Modifier.fillMaxWidth().autofill(listOf(AutofillType.EmailAddress)) { email = it },
 * )
 * ```
 *
 * Uses the experimental autofill API (the only one available on this Compose version); the
 * semantics-based replacement landed in a later release.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.autofill(
    autofillTypes: List<AutofillType>,
    onFill: (String) -> Unit,
): Modifier = composed {
    val currentOnFill by rememberUpdatedState(onFill)
    val autofillNode = remember(autofillTypes) {
        AutofillNode(autofillTypes = autofillTypes, onFill = { currentOnFill(it) })
    }
    val autofill = LocalAutofill.current
    LocalAutofillTree.current += autofillNode

    onGloballyPositioned { autofillNode.boundingBox = it.boundsInWindow() }
        .onFocusChanged { focusState ->
            autofill?.run {
                if (focusState.isFocused) {
                    requestAutofillForNode(autofillNode)
                } else {
                    cancelAutofillForNode(autofillNode)
                }
            }
        }
}

/** Marks a field as an e-mail/username login field for autofill (KeePass etc.). */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.autofillEmail(onFill: (String) -> Unit): Modifier =
    autofill(listOf(AutofillType.EmailAddress, AutofillType.Username), onFill)

/** Marks a field as a password field for autofill. */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.autofillPassword(onFill: (String) -> Unit): Modifier =
    autofill(listOf(AutofillType.Password), onFill)
