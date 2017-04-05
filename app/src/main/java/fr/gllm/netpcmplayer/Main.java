/*
 *  Main          Service running the audio thread
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

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.PowerManager;
import android.support.annotation.MainThread;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

public class Main extends Service implements Runnable {

    private class LocalBinder extends Binder {
        Main getService() {
            return Main.this;
        }
    }

    public static Main getService(IBinder iBinder) {
        LocalBinder binder = (LocalBinder) iBinder;
        return binder.getService();
    }

    interface OnErrorListener {
        void OnError(String error);
    }

    static class Arguments  implements Parcelable {
        final boolean wakelock;
        final int audioSampleRate;
        final int audioChannelMask;
        final int audioEncoding;
        final int audioDelayInMs;
        final int serverPort;
        final String serverBindAddr;

        Arguments(boolean wakelock, int audioSampleRate, int audioChannelMask, int audioEncoding,
                  int audioDelayInMs, int serverPort, String serverBindAddr) {
            this.wakelock = wakelock;
            this.audioSampleRate = audioSampleRate;
            this.audioChannelMask = audioChannelMask;
            this.audioEncoding = audioEncoding;
            this.audioDelayInMs = audioDelayInMs;
            this.serverPort = serverPort;
            this.serverBindAddr = serverBindAddr;
        }

        boolean isValid() {
            return audioSampleRate > 0 && audioChannelMask != -1 && audioEncoding != -1 &&
                    audioDelayInMs > 0 && serverPort > 0 && serverPort < 65536;
        }

        @Override
        public String toString() {
            return "wl: " + wakelock + ", as: " + audioSampleRate + " Hz" + ", ac:" +
                    Integer.bitCount(audioChannelMask) + ", ae: " + audioEncoding + ", ad: " +
                    audioDelayInMs + "ms" + ", sp: " +serverPort + ", sb: " + serverBindAddr;
        }

        Arguments(Parcel in) {
            wakelock = in.readByte() != 0;
            audioSampleRate = in.readInt();
            audioChannelMask = in.readInt();
            audioEncoding = in.readInt();
            audioDelayInMs = in.readInt();
            serverPort = in.readInt();
            serverBindAddr = in.readString();
        }

        public static final Creator<Arguments> CREATOR = new Creator<Arguments>() {
            @Override
            public Arguments createFromParcel(Parcel in) {
                return new Arguments(in);
            }

            @Override
            public Arguments[] newArray(int size) {
                return new Arguments[size];
            }
        };

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel parcel, int i) {
            parcel.writeByte((byte) (wakelock ? 1 : 0));
            parcel.writeInt(audioSampleRate);
            parcel.writeInt(audioChannelMask);
            parcel.writeInt(audioEncoding);
            parcel.writeInt(audioDelayInMs);
            parcel.writeInt(serverPort);
            parcel.writeString(serverBindAddr);
        }

        public boolean equals(Arguments args) {
            return wakelock == args.wakelock && audioSampleRate == args.audioSampleRate &&
                    audioChannelMask == args.audioChannelMask && audioEncoding == args.audioEncoding &&
                    audioDelayInMs == args.audioDelayInMs && serverPort == args.serverPort &&
                    serverBindAddr.equals(args.serverBindAddr);
        }
    }

    static final String TAG = "NPCMP";
    private static final int sMinSocketReadOnceInBytes = 8192;

    private Thread mThread = null;
    private Arguments mArguments = null;
    private boolean mServiceStarted = false;
    private boolean mStopping = false;
    private boolean mRestarting = false;
    private AudioTrack mAudioTrack = null;
    private ServerSocket mServerSocket = null;
    private Socket mSocket = null;
    private int mSocketReadOnceInBytes;
    private final IBinder mBinder = new LocalBinder();
    private PowerManager.WakeLock mWakelock = null;
    private OnErrorListener mOnErrorListener = null;
    private static final int LOG_COUNT = 30;
    private ArrayList<String> mLogList = new ArrayList<>(LOG_COUNT);

    private synchronized void addLog(boolean error, String log, Exception e) {
        final SimpleDateFormat sdf = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault());
        final String time = sdf.format(Calendar.getInstance().getTime());
        mLogList.add(time + " " + (error ? "E" : "V") + "/ " + log);
        if (mLogList.size() > LOG_COUNT)
            mLogList.remove(0);
        if (error)
            Log.e(TAG, log, e);
        else
            Log.d(TAG, log, e);
    }

    private void addLog(boolean error, String log) {
        addLog(error, log, null);
    }

    public synchronized List<String> getLogs() {
        return new ArrayList<>(mLogList);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            final Arguments args = intent.getParcelableExtra("args");
            if (args != null)
                start(args);
        }

        return START_STICKY;
    }

    @SuppressWarnings("InfiniteLoopStatement")
    private void serverPlay() {
        try {
            mSocket.setSoTimeout(8000);
        } catch (SocketException e) {
            addLog(true, "Could not set Socket timeout");
            return;
        }

        mAudioTrack.play();
        try {
            final InputStream is = mSocket.getInputStream();
            final byte[] bytes = new byte[mSocketReadOnceInBytes];

            while (true) {
                final int read = is.read(bytes, 0, mSocketReadOnceInBytes);
                if (read == -1) {
                    mSocket.close();
                    addLog(true, "Socket closed by the client");
                    return;
                }
                mAudioTrack.write(bytes, 0, read);
            }
        } catch (SocketTimeoutException timeoutIgnored) {
            try {
                addLog(true, "The socket time outed: " + mSocket);
                mSocket.close();
            } catch (IOException ignored) {
            }
        } catch (IOException e) {
            synchronized (this) {
                if (!mStopping)
                    addLog(true, "Socket triggered an IOException", e);
            }
        } finally {
            mAudioTrack.flush();
            mAudioTrack.stop();
        }
    }

    private void startService() {
        synchronized (this) {
            if (mServiceStarted)
                return;
            mServiceStarted = true;
        }

        final Intent mainIntent = new Intent(this, SettingsActivity.class);
        mainIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP |
                Intent.FLAG_ACTIVITY_SINGLE_TOP);
        final PendingIntent contentIntent = PendingIntent.getActivity(this, 0,
                mainIntent, PendingIntent.FLAG_CANCEL_CURRENT);

        final Notification notification = new Notification.Builder(this)
                .setContentTitle(getText(R.string.notification_title_npcmp_running))
                .setContentText(getText(R.string.notification_text_npcmp_running))
                .setContentIntent(contentIntent)
                .setSmallIcon(R.mipmap.ic_launcher)
                .build();

        startForeground(R.string.notification_title_npcmp_running, notification);
        startService(new Intent(this, Main.class));
        addLog(false, "Main Service started");
    }

    private void stopService() {
        synchronized (this) {
            if (mRestarting)
                return;
            mServiceStarted = false;
        }

        stopForeground(true);
        stopSelf();
        addLog(false, "Main Service stopped");
    }

    @Override
    public void run() {
        if (!mArguments.isValid()) {
            quitThread("Arguments invalid");
            return;
        }
        addLog(false, "starting with " + mArguments);

        if (!createAudioTrack(mArguments))
            return;

        setWakelockEnabled(mArguments.wakelock);

        startService();

        while (true) {
            synchronized (this) {
                mSocket = null;
                if (mStopping) {
                    mServerSocket = null;
                    break;
                }
                if (mServerSocket == null && !createSocketServer(mArguments)) {
                    break;
                }
            }
            try {
                final Socket socket = mServerSocket.accept();
                synchronized (this) {
                    if (mServerSocket.isClosed())
                        continue;
                    mSocket = socket;
                }
            } catch (IOException e) {
                synchronized (this) {
                    if (!mStopping)
                        addLog(true, "ServerSocket triggered an IOException", e);
                    mServerSocket = null;
                }
                continue;
            }
            addLog(false, "New socket accepted: " + mSocket);
            serverPlay();
        }

        setWakelockEnabled(false);

        mAudioTrack.release();
        mAudioTrack = null;
        quitThread("Thread terminated");
    }

    private void quitThread(String error, Exception e) {
        synchronized (this) {
            if (!mStopping) {
                if (mOnErrorListener != null)
                    mOnErrorListener.OnError(error);
                addLog(true, "thread: " + error, e);
            }
        }
        stopService();
    }

    private void quitThread(String error) {
        quitThread(error, null);
    }

    private int setupBufferSizes(Arguments args) {
        if (args.audioDelayInMs == 0 || args.audioDelayInMs > 60000) {
            quitThread("audioDelayInMs is invalid");
            return -1;
        }

        final int minAtBufferSizeInBytes = AudioTrack.getMinBufferSize(args.audioSampleRate,
                args.audioChannelMask, args.audioEncoding);
        if (minAtBufferSizeInBytes <= 0) {
            quitThread("getMinBufferSize failed");
            return -1;
        }

        final int nbChannels = Integer.bitCount(args.audioChannelMask);
        if (nbChannels == 0 || nbChannels > 8) {
            quitThread("invalid channel mask");
            return -1;
        }
        int bytesPerFrames;
        switch (args.audioEncoding) {
            case AudioFormat.ENCODING_PCM_8BIT:
                bytesPerFrames = 1;
                break;
            case AudioFormat.ENCODING_PCM_16BIT:
                bytesPerFrames = 2;
                break;
            default:
                quitThread("invalid audioEncoding");
                return -1;
        }

        long delayInBytes = (long) args.audioDelayInMs * args.audioSampleRate / 1000 * bytesPerFrames;

        int atBufferSizeInBytes;
        if (delayInBytes - (2 * minAtBufferSizeInBytes) >= sMinSocketReadOnceInBytes) {
            mSocketReadOnceInBytes = (int) delayInBytes - (2 * minAtBufferSizeInBytes);
            atBufferSizeInBytes = 2 * minAtBufferSizeInBytes;

        } else if (delayInBytes - sMinSocketReadOnceInBytes  >= minAtBufferSizeInBytes) {
            mSocketReadOnceInBytes = sMinSocketReadOnceInBytes;
            atBufferSizeInBytes = (int) delayInBytes - sMinSocketReadOnceInBytes;
        } else {
            quitThread("Delay is too low");
            return -1;
        }

        addLog(false, "delays in Bytes: total: " + delayInBytes +", minAt: " +
                minAtBufferSizeInBytes + ", readOnce: " + mSocketReadOnceInBytes + ", at: " + atBufferSizeInBytes);
        return atBufferSizeInBytes;
    }

    private boolean createAudioTrack(Arguments args) {
        final int atBufferSizeInBytes = setupBufferSizes(args);

        if (atBufferSizeInBytes == -1)
            return false;

        final AudioManager au = (AudioManager) getSystemService(AUDIO_SERVICE);
        final int sessionId = au.generateAudioSessionId();
        if (sessionId == -1) {
            quitThread("generateAudioSessionId failed");
            return false;
        }

        final AudioAttributes at = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
        final AudioFormat af = new AudioFormat.Builder()
                .setSampleRate(args.audioSampleRate)
                .setChannelMask(args.audioChannelMask)
                .setEncoding(args.audioEncoding)
                .build();
        try {
            mAudioTrack = new AudioTrack(at, af, atBufferSizeInBytes, AudioTrack.MODE_STREAM, sessionId);
            if (mAudioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
                mAudioTrack.release();
                mAudioTrack = null;
                quitThread("AudioTrack creation failed");
                return false;
            }
        } catch (Exception e) {
            quitThread("AudioTrack creation failed", e);
            return false;
        }
        return true;
    }

    private boolean createSocketServer(Arguments args) {
        try {
            mServerSocket = new ServerSocket();
            mServerSocket.setReceiveBufferSize(mSocketReadOnceInBytes);
            mServerSocket.bind(args.serverBindAddr != null ?
                    new InetSocketAddress(args.serverBindAddr, args.serverPort) :
                    new InetSocketAddress(args.serverPort));
        } catch (Exception e) {
            quitThread("ServerSocket creation failed", e);
            return false;
        }
        return true;
    }

    @MainThread
    public void start(Arguments args) {
        if (mArguments != null && mArguments.equals(args))
            return;
        stop(true);

        mArguments = args;

        mThread = new Thread(this);
        mThread.start();
    }

    private void stop(boolean restarting) {
        if (mThread != null) {

            addLog(false, "Stopping the Main thread from the user");

            synchronized (this) {
                mStopping = true;
                mRestarting = restarting;
                if (mThread.isAlive()) {
                    try {
                        if (mServerSocket != null)
                            mServerSocket.close();
                        if (mSocket != null)
                            mSocket.close();
                    } catch (IOException ignored) {}
                }
            }

            try {
                mThread.join(10000);
                if (mThread.isAlive()) {
                    quitThread("NetPCMPlayer process doesn't respond to shutdown, force kill");
                    System.exit(-1);
                }
                mThread = null;
                addLog(false, "Main thread joined");
            } catch (InterruptedException ignored) {}

            synchronized (this) {
                mRestarting = mStopping = false;
            }
        }
        mArguments = null;
    }

    @MainThread
    public void stop() {
        stop(false);
    }

    @MainThread
    public synchronized void setOnErrorListener(OnErrorListener OnErrorListener) {
        mOnErrorListener = OnErrorListener;
    }

    @MainThread
    public void setWakelockEnabled(boolean enabled) {
        if (enabled && mWakelock == null) {
            PowerManager pm = (PowerManager)getSystemService(Context.POWER_SERVICE);
            mWakelock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
            mWakelock.acquire();
            addLog(false, "Wakelock acquired");
        } else if (!enabled && mWakelock != null) {
            mWakelock.release();
            mWakelock = null;
            addLog(false, "Wakelock released");
        }
    }

    @MainThread
    public boolean isRunning() {
        return mThread != null && mThread.isAlive();
    }

    /*
     * start Main service without any callback
     */
    @MainThread
    public static void start(Context context, Arguments args) {
        context.startService(new Intent(context, Main.class).putExtra("args", args));
    }
}
