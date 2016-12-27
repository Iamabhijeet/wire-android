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
package com.waz.zclient.controllers.vibrator;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.media.AudioManager;
import android.os.Vibrator;
import android.support.annotation.ArrayRes;
import android.support.annotation.NonNull;
import com.waz.zclient.R;
import com.waz.zclient.controllers.userpreferences.UserPreferencesController;

public class VibratorController implements IVibratorController {

    private Vibrator vibrator;
    private AudioManager audioManager;
    private Context context;

    public VibratorController(Context context) {
        this.context = context;
        this.vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        this.audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    }

    @Override
    public void tearDown() {
        context = null;
        audioManager = null;
        vibrator = null;
    }


    //TODO remove this class when vibrate method can be moved to SoundController
    @Override
    public void vibrate(@NonNull long[] pattern) {
        if (audioManager.getRingerMode() == AudioManager.RINGER_MODE_SILENT ||
            !vibrator.hasVibrator()) {
            return;
        }
        if (!isEnabledInPreferences(context)) {
            return;
        }
        vibrator.cancel();
        vibrator.vibrate(pattern, -1);
    }


    @SuppressWarnings("PMD.AvoidArrayLoops")
    public static long[] resolveResource(Resources resources, @ArrayRes int resId) {
        int[] intArray = resources.getIntArray(resId);
        long[] longArray = new long[intArray.length];
        for (int i = 0; i < intArray.length; i++) {
            longArray[i] = intArray[i];
        }
        return longArray;
    }

    public static boolean isEnabledInPreferences(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(UserPreferencesController.USER_PREFS_TAG, Context.MODE_PRIVATE);
        return preferences.getBoolean(context.getString(R.string.pref_options_vibration_key), true);
    }
}
