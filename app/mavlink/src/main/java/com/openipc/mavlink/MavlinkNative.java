package com.openipc.mavlink;

import android.content.Context;

public class MavlinkNative {

    // Used to load the 'mavlink' library on application startup.
    static {
        System.loadLibrary("mavlink");
    }

    public static native void nativeStart(Context context);

    public static native void nativeStop(Context context);

    public static native void nativeConfigureForward(String[] ips, int[] ports, boolean enabled);

    public static native void nativeSetRawForwarder(MavlinkRawForwarder forwarder);

    // TODO: Use message queue from cpp for performance#
    // This initiates a 'call back' for the IVideoParams
    public static native <T extends MavlinkUpdate> void nativeCallBack(T t);
}
