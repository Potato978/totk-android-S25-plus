package com.aure.totk;

import android.os.SystemClock;
import android.util.Log;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.CRC32;

/** Lightweight Cemuhook DSU (UDP 127.0.0.1:26760) motion client for Joycon2Android
 * (Switch 2 Pro Controller / Joy-Con 2 gyro + accelerometer). Pure Android SDK, zero deps. */
final class DsuMotionClient {
    private static final String TAG = "TotkDsu";
    private static final int PORT = 26760;
    private static final int MSG_CONTROLLER_DATA = 0x100002;
    private static final float G_TO_MS2 = 9.80665f;
    private static final float DEG_TO_RAD = (float) (Math.PI / 180.0);

    private volatile boolean running;
    private volatile long lastPacketMs;
    private Thread thread;
    private DatagramSocket socket;
    private boolean reportedLive;

    boolean isActive() {
        return running && (SystemClock.elapsedRealtime() - lastPacketMs) < 500L;
    }

    synchronized void start() {
        if (running) return;
        running = true;
        reportedLive = false;
        thread = new Thread(this::loop, "totk-dsu-gyro");
        thread.setDaemon(true);
        thread.start();
    }

    synchronized void stop() {
        running = false;
        if (socket != null) {
            try { socket.close(); } catch (Throwable ignored) {}
            socket = null;
        }
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
    }

    private void loop() {
        byte[] recvBuf = new byte[128];
        float[] accel = new float[3];
        float[] gyro = new float[3];
        float[] frame = new float[6];
        int clientId = (int) (SystemClock.elapsedRealtime() & 0x7fffffff);

        try (DatagramSocket sock = new DatagramSocket()) {
            socket = sock;
            sock.setSoTimeout(300);
            InetAddress localhost = InetAddress.getByName("127.0.0.1");
            byte[] reg = buildRegisterPacket(clientId, (byte) 0);
            DatagramPacket out = new DatagramPacket(reg, reg.length, localhost, PORT);
            DatagramPacket in = new DatagramPacket(recvBuf, recvBuf.length);
            long lastReg = 0L;

            while (running) {
                long now = SystemClock.elapsedRealtime();
                if (now - lastReg >= 1500L) {
                    try { sock.send(out); } catch (Throwable ignored) {}
                    lastReg = now;
                }
                try {
                    sock.receive(in);
                    if (in.getLength() < 100) continue;
                    ByteBuffer bb = ByteBuffer.wrap(recvBuf, 0, in.getLength()).order(ByteOrder.LITTLE_ENDIAN);
                    // Magic "DSUS"
                    if (bb.get(0) != 'D' || bb.get(1) != 'S' || bb.get(2) != 'U' || bb.get(3) != 'S') continue;
                    int msgType = bb.getInt(16);
                    if (msgType != MSG_CONTROLLER_DATA) continue;
                    int slotState = bb.get(21) & 0xff;
                    if (slotState != 2) continue; // 2 = connected

                    // Cemuhook DSU payload: accel (g) at 76..87, gyro (deg/s) at 88..99
                    float ax = bb.getFloat(76) * G_TO_MS2;
                    float ay = bb.getFloat(80) * G_TO_MS2;
                    float az = bb.getFloat(84) * G_TO_MS2;
                    float gp = bb.getFloat(88) * DEG_TO_RAD;
                    float gy = bb.getFloat(92) * DEG_TO_RAD;
                    float gr = bb.getFloat(96) * DEG_TO_RAD;

                    if (!Float.isFinite(ax) || !Float.isFinite(gp)) continue;
                    accel[0] = ax; accel[1] = ay; accel[2] = az;
                    gyro[0] = gp;  gyro[1] = gy;  gyro[2] = gr;

                    lastPacketMs = SystemClock.elapsedRealtime();
                    if (!reportedLive) {
                        reportedLive = true;
                        Log.i(TAG, "DSU controller motion connected (127.0.0.1:26760)");
                        TotkActivity.nativeMotionLive(true, "DSU controller (Joycon2Android)");
                    }
                    MotionAxes.toSwitch(accel, gyro, 0, frame);
                    TotkActivity.nativeMotion(frame[0], frame[1], frame[2], frame[3], frame[4], frame[5]);
                } catch (java.net.SocketTimeoutException timeout) {
                    if (reportedLive && (SystemClock.elapsedRealtime() - lastPacketMs) > 2000L) {
                        reportedLive = false;
                        Log.i(TAG, "DSU controller motion idle; falling back to device gyro");
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable failed) {
            Log.w(TAG, "DSU motion client stopped", failed);
        } finally {
            socket = null;
        }
    }

    private static byte[] buildRegisterPacket(int clientId, byte slot) {
        // 16-byte header + 4-byte msgType + 8-byte payload (flags=1 slot-based, slot, 6-byte MAC)
        ByteBuffer bb = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN);
        bb.put((byte) 'D').put((byte) 'S').put((byte) 'U').put((byte) 'C');
        bb.putShort((short) 1001); // protocol version
        bb.putShort((short) 12);   // payload length (msgType + 8)
        bb.putInt(0);              // CRC32 placeholder
        bb.putInt(clientId);
        bb.putInt(MSG_CONTROLLER_DATA);
        bb.put((byte) 1);          // register by slot
        bb.put(slot);
        for (int i = 0; i < 6; i++) bb.put((byte) 0);
        byte[] bytes = bb.array();
        CRC32 crc = new CRC32();
        crc.update(bytes);
        bb.putInt(8, (int) crc.getValue());
        return bytes;
    }
}
