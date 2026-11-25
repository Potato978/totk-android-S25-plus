package com.aure.totk;

import android.os.SystemClock;
import android.util.Log;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;
import java.util.zip.CRC32;

/** Cemuhook DSU (UDP 127.0.0.1:26760) motion client modeled on Eden/Yuzu's udp_client.
 * Works with Joycon2Android (Switch 2 Pro Controller / Joy-Con 2). Pure Android SDK. */
public final class DsuMotionClient {
    private static final String TAG = "TotkDsu";
    public static final String DEFAULT_HOST = "127.0.0.1";
    public static final int DEFAULT_PORT = 26760;
    private static final int MSG_PORT_INFO = 0x100001;
    private static final int MSG_CONTROLLER_DATA = 0x100002;
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

    /** One-shot diagnostic probe used by S25+ Tweaks & QoL -> Test DSU Gyro. Runs on a background thread. */
    public static String probeOnce(String host, int port) {
        byte[] recvBuf = new byte[128];
        int clientId = (int) (SystemClock.elapsedRealtime() & 0x7fffffff);
        try (DatagramSocket sock = new DatagramSocket()) {
            sock.setSoTimeout(1200);
            InetAddress addr = InetAddress.getByName(host);
            byte[] infoPkt = buildInfoPacket(clientId);
            byte[] regAll = buildRegisterPacket(clientId, (byte) 0, (byte) 0);
            byte[] reg0 = buildRegisterPacket(clientId, (byte) 1, (byte) 0);
            sock.send(new DatagramPacket(infoPkt, infoPkt.length, addr, port));
            sock.send(new DatagramPacket(regAll, regAll.length, addr, port));
            sock.send(new DatagramPacket(reg0, reg0.length, addr, port));

            DatagramPacket in = new DatagramPacket(recvBuf, recvBuf.length);
            long deadline = SystemClock.elapsedRealtime() + 1800L;
            int infoReplies = 0;
            while (SystemClock.elapsedRealtime() < deadline) {
                try {
                    sock.receive(in);
                    if (in.getLength() < 24) continue;
                    ByteBuffer bb = ByteBuffer.wrap(recvBuf, 0, in.getLength()).order(ByteOrder.LITTLE_ENDIAN);
                    if (bb.get(0) != 'D' || bb.get(1) != 'S' || bb.get(2) != 'U' || bb.get(3) != 'S') continue;
                    int msgType = bb.getInt(16);
                    if (msgType == MSG_PORT_INFO) { infoReplies++; continue; }
                    if (msgType == MSG_CONTROLLER_DATA && in.getLength() >= 100) {
                        int slot = bb.get(20) & 0xff;
                        int state = bb.get(21) & 0xff;
                        float ax = bb.getFloat(76), ay = bb.getFloat(80), az = bb.getFloat(84);
                        float gp = bb.getFloat(88), gy = bb.getFloat(92), gr = bb.getFloat(96);
                        return String.format(Locale.ROOT,
                                "CONNECTED! Slot %d (state=%d)\nAccel(g): %.2f, %.2f, %.2f\nGyro(deg/s): P=%.1f, Y=%.1f, R=%.1f",
                                slot, state, ax, ay, az, gp, gy, gr);
                    }
                } catch (java.net.SocketTimeoutException ignored) {
                    break;
                }
            }
            if (infoReplies > 0) {
                return "DSU server replied (" + infoReplies + " port info packets) on " + host + ":" + port
                        + ", but no controller is streaming motion yet.\nAssign your Switch 2 Pro Controller to Player 1 in Joycon2Android.";
            }
            return "No response from " + host + ":" + port + ".\n\nIn Joycon2Android:\n1. Connect your Switch 2 Pro Controller & assign to Player 1.\n2. On the Output card, choose 'Other emulator' (or enable Motion/DSU) so status shows 'On: gamepad, motion'.";
        } catch (Throwable err) {
            return "DSU test error: " + err;
        }
    }

    private void loop() {
        byte[] recvBuf = new byte[128];
        int clientId = (int) (SystemClock.elapsedRealtime() & 0x7fffffff);

        try (DatagramSocket sock = new DatagramSocket()) {
            socket = sock;
            sock.setSoTimeout(250);
            InetAddress localhost = InetAddress.getByName(DEFAULT_HOST);

            byte[] infoPkt = buildInfoPacket(clientId);
            byte[] regAll = buildRegisterPacket(clientId, (byte) 0, (byte) 0);
            byte[] reg0 = buildRegisterPacket(clientId, (byte) 1, (byte) 0);
            byte[] reg1 = buildRegisterPacket(clientId, (byte) 1, (byte) 1);
            DatagramPacket outInfo = new DatagramPacket(infoPkt, infoPkt.length, localhost, DEFAULT_PORT);
            DatagramPacket outAll = new DatagramPacket(regAll, regAll.length, localhost, DEFAULT_PORT);
            DatagramPacket out0 = new DatagramPacket(reg0, reg0.length, localhost, DEFAULT_PORT);
            DatagramPacket out1 = new DatagramPacket(reg1, reg1.length, localhost, DEFAULT_PORT);
            DatagramPacket in = new DatagramPacket(recvBuf, recvBuf.length);
            long lastReg = 0L;

            while (running) {
                long now = SystemClock.elapsedRealtime();
                if (now - lastReg >= 1500L) {
                    try {
                        sock.send(outInfo);
                        sock.send(outAll);
                        sock.send(out0);
                        sock.send(out1);
                    } catch (Throwable ignored) {}
                    lastReg = now;
                }
                try {
                    sock.receive(in);
                    if (in.getLength() < 100) continue;
                    ByteBuffer bb = ByteBuffer.wrap(recvBuf, 0, in.getLength()).order(ByteOrder.LITTLE_ENDIAN);
                    if (bb.get(0) != 'D' || bb.get(1) != 'S' || bb.get(2) != 'U' || bb.get(3) != 'S') continue;
                    int msgType = bb.getInt(16);
                    if (msgType != MSG_CONTROLLER_DATA) continue;
                    int slotState = bb.get(21) & 0xff;
                    if (slotState == 0) continue;

                    // Cemuhook DSU payload (G and deg/s)
                    float dsuAx = bb.getFloat(76);
                    float dsuAy = bb.getFloat(80);
                    float dsuAz = bb.getFloat(84);
                    float pitch = bb.getFloat(88) * DEG_TO_RAD;
                    float yaw   = bb.getFloat(92) * DEG_TO_RAD;
                    float roll  = bb.getFloat(96) * DEG_TO_RAD;

                    if (!Float.isFinite(dsuAx) || !Float.isFinite(pitch)) continue;

                    // Eden/Yuzu DSU -> TotK Native MotionAxes frame (x=right, y=forward, z=up; flat z=-1g)
                    float ax = dsuAx;
                    float ay = -dsuAz;
                    float az = dsuAy;
                    float gx = pitch;
                    float gy = -roll;
                    float gz = yaw;

                    lastPacketMs = SystemClock.elapsedRealtime();
                    if (!reportedLive) {
                        reportedLive = true;
                        int slot = bb.get(20) & 0xff;
                        Log.i(TAG, "DSU controller motion connected on slot " + slot);
                        TotkActivity.nativeMotionLive(true, "DSU slot " + slot + " (Joycon2Android)");
                    }
                    TotkActivity.nativeMotion(ax, ay, az, gx, gy, gz);
                } catch (java.net.SocketTimeoutException timeout) {
                    if (reportedLive && (SystemClock.elapsedRealtime() - lastPacketMs) > 2000L) {
                        reportedLive = false;
                        Log.i(TAG, "DSU controller motion idle; falling back to device gyro");
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable failed) {
            Log.w(TAG, "DSU motion client error", failed);
        } finally {
            socket = null;
        }
    }

    private static byte[] buildInfoPacket(int clientId) {
        ByteBuffer bb = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN);
        bb.put((byte) 'D').put((byte) 'S').put((byte) 'U').put((byte) 'C');
        bb.putShort((short) 1001);
        bb.putShort((short) 12);
        bb.putInt(0);
        bb.putInt(clientId);
        bb.putInt(MSG_PORT_INFO);
        bb.putInt(4);
        bb.put((byte) 0).put((byte) 1).put((byte) 2).put((byte) 3);
        byte[] bytes = bb.array();
        CRC32 crc = new CRC32();
        crc.update(bytes);
        bb.putInt(8, (int) crc.getValue());
        return bytes;
    }

    private static byte[] buildRegisterPacket(int clientId, byte flags, byte slot) {
        ByteBuffer bb = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN);
        bb.put((byte) 'D').put((byte) 'S').put((byte) 'U').put((byte) 'C');
        bb.putShort((short) 1001);
        bb.putShort((short) 12);
        bb.putInt(0);
        bb.putInt(clientId);
        bb.putInt(MSG_CONTROLLER_DATA);
        bb.put(flags);
        bb.put(slot);
        for (int i = 0; i < 6; i++) bb.put((byte) 0);
        byte[] bytes = bb.array();
        CRC32 crc = new CRC32();
        crc.update(bytes);
        bb.putInt(8, (int) crc.getValue());
        return bytes;
    }
}
