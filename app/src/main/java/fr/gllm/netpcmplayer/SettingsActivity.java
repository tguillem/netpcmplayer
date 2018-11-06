/*
 *  SettingsActivity  Activity to configure the audio service
 *  Copyright (c)     2017 Thomas Guillem <thomas@gllm.fr>
 *                    All Rights Reserved
 *
 *  This program is free software. It comes without any warranty, to
 *  the extent permitted by applicable law. You can redistribute it
 *  and/or modify it under the terms of the Do What the Fuck You Want
 *  to Public License, Version 2, as published by Sam Hocevar. See
 *  http://www.wtfpl.net/ for more details.
 */

package fr.gllm.netpcmplayer;

import android.annotation.TargetApi;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.preference.EditTextPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceManager;
import android.preference.SwitchPreference;
import android.util.Log;
import android.widget.Toast;

import java.util.List;


public class SettingsActivity extends AppCompatPreferenceActivity {

    static final String KEY_RUN_ON_BOOT ="general_run_on_boot";
    static final String KEY_WAKELOCK ="general_wakelock";
    static final String KEY_AUDIO_ENCODING ="audio_encoding";
    static final String KEY_AUDIO_SAMPLE_RATE ="audio_samplerate";
    static final String KEY_AUDIO_CHANNELS ="audio_channels";
    static final String KEY_AUDIO_DELAY ="audio_delay";
    static final String KEY_IS_SERVER = "is_server";
    static final String PORT ="port";
    static final String ADDRESS ="address";

    private static boolean isXLargeTablet(Context context) {
        return (context.getResources().getConfiguration().screenLayout
                & Configuration.SCREENLAYOUT_SIZE_MASK) >= Configuration.SCREENLAYOUT_SIZE_XLARGE;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getFragmentManager().beginTransaction().replace(android.R.id.content,
                new GeneralPreferenceFragment()).commit();
    }

    @Override
    public boolean onIsMultiPane() {
        return isXLargeTablet(this);
    }

    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    public static class GeneralPreferenceFragment extends PreferenceFragment {

        private Handler mHandler = new Handler(Looper.getMainLooper());
        private Main mMain;
        private SwitchPreference mRunPref;
        private SwitchPreference mRunOnBootPref;
        private EditTextPreference mAddressPref;

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            addPreferencesFromResource(R.xml.pref_general);

            final SharedPreferences sharedPrefs =
                    PreferenceManager.getDefaultSharedPreferences(getActivity());
            findPreference("audio_delay").setSummary(sharedPrefs.getString("audio_delay", ""));
            findPreference("port").setSummary(sharedPrefs.getString("port", ""));
            findPreference("address").setSummary(sharedPrefs.getString("address", ""));

            /* Setup native sample rate at first boot */
            if (sharedPrefs.getString("audio_samplerate", "-1").equals("-1")) {
                AudioManager au = (AudioManager) getActivity().getSystemService(AUDIO_SERVICE);
                String nativeSampleRateProp = au.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE);
                String nativeSampleRate = "44100";
                try {
                    int nativeSampleRateValue = Integer.parseInt(nativeSampleRateProp);
                    switch (nativeSampleRateValue) {
                        case 8000:
                        case 48000:
                        case 96000:
                        case 44100:
                            nativeSampleRate = nativeSampleRateProp;
                            break;
                    }
                } catch (NumberFormatException ignored) {
                }
                ((ListPreference)findPreference("audio_samplerate")).setValue(nativeSampleRate);
            }
        }

        private void setupPreferences() {
            mRunPref = (SwitchPreference) findPreference("general_run");
            mRunPref.setOnPreferenceChangeListener(mRunListener);

            mAddressPref = (EditTextPreference) findPreference("address");
            mAddressPref.setOnPreferenceChangeListener(mAddressListener);

            if (mMain.isRunning())
                mRunPref.setChecked(true);

            mRunOnBootPref = (SwitchPreference) findPreference("general_run_on_boot");
            mRunOnBootPref.setOnPreferenceChangeListener(mRunOnBootListener);

            findPreference("general_wakelock").setOnPreferenceChangeListener(mWakelockListener);
            findPreference("audio_encoding").setOnPreferenceChangeListener(mGeneralListener);
            findPreference("audio_samplerate").setOnPreferenceChangeListener(mGeneralListener);
            findPreference("audio_channels").setOnPreferenceChangeListener(mGeneralListener);
            findPreference("audio_delay").setOnPreferenceChangeListener(mAudioDelayListener);
            findPreference("is_server").setOnPreferenceChangeListener(mIsServerListener);
            findPreference("port").setOnPreferenceChangeListener(mPortListener);

            if (mRunOnBootPref.isChecked()) {
                mRunPref.setChecked(true);
                start();
            }
        }

        private final Preference.OnPreferenceChangeListener mRunListener =
                new Preference.OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object value) {
                if ((Boolean) value)
                    start();
                else
                    mMain.stop();
                return true;
            }
        };

        private final Preference.OnPreferenceChangeListener mRunOnBootListener =
                new Preference.OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object value) {
                if ((Boolean) value && !mRunPref.isChecked()) {
                    mRunPref.setChecked(true);
                    start();
                }
                return true;
            }
        };

        private final Preference.OnPreferenceChangeListener mWakelockListener =
                new Preference.OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object value) {
                if (mMain.isRunning())
                    mMain.setWakelockEnabled((Boolean) value);
                return true;
            }
        };

        private final Preference.OnPreferenceChangeListener mGeneralListener =
                new Preference.OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object value) {
                restartIfNeeded();
                return true;
            }
        };

        private final Preference.OnPreferenceChangeListener mAudioDelayListener =
                new Preference.OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object value) {
                String stringValue = value.toString();
                try {
                    int delay = Integer.parseInt(stringValue);
                    if (delay > 0 && delay <= 60000) {
                        preference.setSummary(stringValue);
                        restartIfNeeded();
                        return true;
                    } else
                        Toast.makeText(getActivity(), "Audio delay is too big", Toast.LENGTH_SHORT)
                                .show();
                } catch (NumberFormatException ignored) {
                }
                return false;
            }
        };

        private final Preference.OnPreferenceChangeListener mIsServerListener =
                new Preference.OnPreferenceChangeListener() {
                    @Override
                    public boolean onPreferenceChange(Preference preference, Object value) {
                        mAddressPref.setTitle((Boolean) value ? R.string.server_address_title
                                : R.string.address_title);
                        restartIfNeeded();
                        return true;
                    }
                };
        private final Preference.OnPreferenceChangeListener mPortListener =
                new Preference.OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object value) {
                String stringValue = value.toString();
                try {
                    int port = Integer.parseInt(stringValue);
                    if (port > 0 && port <= 65536) {
                        preference.setSummary(stringValue);
                        restartIfNeeded();
                        return true;
                    } else
                        Toast.makeText(getActivity(), "Server port is invalid", Toast.LENGTH_SHORT)
                                .show();
                } catch (NumberFormatException ignored) {
                }
                return false;
            }
        };

        private final Preference.OnPreferenceChangeListener mAddressListener =
                new Preference.OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object value) {
                preference.setSummary(value.toString());
                restartIfNeeded();
                return true;
            }
        };

        private Main.OnErrorListener mOnErrorListener = new Main.OnErrorListener() {
            @Override
            public void OnError(final String error) {
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        mRunPref.setChecked(false);
                        mRunOnBootPref.setChecked(false);
                        Toast.makeText(getActivity(), error, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        };

        private void start() {
            mMain.start(getMainArguments(getActivity()));
        }

        private final Runnable mRestartRunnable = new Runnable() {
            @Override
            public void run() {
                if (mRunPref.isChecked())
                    start();
            }
        };

        private void restartIfNeeded() {
            mHandler.removeCallbacks(mRestartRunnable);
            mHandler.post(mRestartRunnable);
        }

        private final ServiceConnection mServiceConnection = new ServiceConnection() {

            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                mMain = Main.getService(service);
                mMain.setOnErrorListener(mOnErrorListener);
                List<String> logs = mMain.getLogs();
                if (logs.size() > 0) {
                    Log.d(Main.TAG, ">>> last logs of the Main service >>>");
                    for (String log : logs)
                        Log.d(Main.TAG, log);
                    Log.d(Main.TAG, "<<< last logs of the Main service <<<");
                }
                setupPreferences();
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                disconnectService();
            }
        };

        private void connectService() {
            getActivity().bindService(new Intent(getActivity(), Main.class), mServiceConnection,
                    Context.BIND_AUTO_CREATE);
        }

        private void disconnectService() {
            mMain.setOnErrorListener(null);
            mMain = null;
            getActivity().unbindService(mServiceConnection);
        }

        @Override
        public void onStart() {
            connectService();
            super.onStart();
        }

        @Override
        public void onStop() {
            disconnectService();
            super.onStop();
        }
    }

    static int getIntPref(SharedPreferences prefs, String key) {
        try {
            return Integer.parseInt(prefs.getString(key, "-1"));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    static Main.Arguments getMainArguments(Context context) {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences (context);
        return new Main.Arguments(prefs.getBoolean(KEY_WAKELOCK, false),
                getIntPref(prefs, KEY_AUDIO_SAMPLE_RATE),
                getIntPref(prefs, KEY_AUDIO_CHANNELS),
                getIntPref(prefs, KEY_AUDIO_ENCODING),
                getIntPref(prefs, KEY_AUDIO_DELAY),
                prefs.getBoolean(KEY_IS_SERVER, false),
                getIntPref(prefs, PORT),
                prefs.getString(ADDRESS, ""));
    }
}