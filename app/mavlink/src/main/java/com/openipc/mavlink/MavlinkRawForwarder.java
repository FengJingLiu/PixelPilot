package com.openipc.mavlink;

public interface MavlinkRawForwarder {
    void onMavlinkRaw(byte[] data, int length);
}
