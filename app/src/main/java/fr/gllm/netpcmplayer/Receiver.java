/*
 *  Receiver      Receiver that start the service at boot if needed
 *  Copyright (c) 2017 Thomas Guillem <thomas@gllm.fr>
 *                All Rights Reserved
 *
 *  This program is free software. It comes without any warranty, to
 *  the extent permitted by applicable law. You can redistribute it
 *  and/or modify it under the terms of the Do What the Fuck You Want
 *  to Public License, Version 2, as published by Sam Hocevar. See
 *  http://www.wtfpl.net/ for more details.
 */
package fr.gllm.netpcmplayer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.util.Log;

public class Receiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d("Receiver", "onReceive: " + intent);
        if (intent.getAction().equals("android.intent.action.BOOT_COMPLETED")) {
            final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences (context);

            if (prefs.getBoolean(SettingsActivity.KEY_RUN_ON_BOOT, false)) {
                Main.start(context, SettingsActivity.getMainArguments(context));
            }
        }
    }
}
