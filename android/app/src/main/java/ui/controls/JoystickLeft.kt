/*
    Copyright (C) 2018, 2019 Ilya Zhuravlev

    This file is part of OpenMW-Android.

    OpenMW-Android is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    OpenMW-Android is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with OpenMW-Android.  If not, see <https://www.gnu.org/licenses/>.
*/

package ui.controls

import android.content.Context
import androidx.core.math.MathUtils
import android.util.AttributeSet
import android.view.KeyEvent
import org.libsdl.app.SDLActivity

class JoystickLeft : Joystick {

    private var wDown = false
    private var aDown = false
    private var sDown = false
    private var dDown = false

    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet, defStyle: Int)
        : super(context, attrs, defStyle)

    private fun setKeyState(keyCode: Int, currentlyDown: Boolean, shouldBeDown: Boolean): Boolean {
        if (currentlyDown == shouldBeDown)
            return currentlyDown

        if (shouldBeDown) {
            SDLActivity.onNativeKeyDown(keyCode)
        } else {
            SDLActivity.onNativeKeyUp(keyCode)
        }

        return shouldBeDown
    }

    private fun releaseAllMovementKeys() {
        wDown = setKeyState(KeyEvent.KEYCODE_W, wDown, false)
        aDown = setKeyState(KeyEvent.KEYCODE_A, aDown, false)
        sDown = setKeyState(KeyEvent.KEYCODE_S, sDown, false)
        dDown = setKeyState(KeyEvent.KEYCODE_D, dDown, false)
    }

    override fun updateStick() {
        if (down) {
            // Convert joystick drag into normalized movement and map to WASD.
            val w = (width / 3).toFloat()
            var diffX = currentX - initialX
            var diffY = currentY - initialY

            val bias = 0.3f

            if (Math.abs(diffX) > Math.abs(diffY)) {
                diffY = Math.signum(diffY) * Math.max(0f, Math.abs(diffY) - bias * Math.abs(diffX))
            } else {
                diffX = Math.signum(diffX) * Math.max(0f, Math.abs(diffX) - bias * Math.abs(diffY))
            }

            val dx = MathUtils.clamp(diffX / w + 0.2f * Math.signum(diffX), -1f, 1f)
            val dy = MathUtils.clamp(diffY / w + 0.2f * Math.signum(diffY), -1f, 1f)

            val deadzone = 0.25f
            val wantA = dx < -deadzone
            val wantD = dx > deadzone
            val wantW = dy < -deadzone
            val wantS = dy > deadzone

            aDown = setKeyState(KeyEvent.KEYCODE_A, aDown, wantA)
            dDown = setKeyState(KeyEvent.KEYCODE_D, dDown, wantD)
            wDown = setKeyState(KeyEvent.KEYCODE_W, wDown, wantW)
            sDown = setKeyState(KeyEvent.KEYCODE_S, sDown, wantS)
        } else {
            releaseAllMovementKeys()
        }
    }
}
