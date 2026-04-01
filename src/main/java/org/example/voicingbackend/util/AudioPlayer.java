package org.example.voicingbackend.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class AudioPlayer {
    private static final Logger logger = LoggerFactory.getLogger(AudioPlayer.class);

    public static void saveWav(float[] samples, int sampleRate, String filename) throws Exception {
        byte[] byteBuffer = new byte[samples.length * 2];
        int idx = 0;

        for (float sample : samples) {
            sample = Math.max(-1.0f, Math.min(1.0f, sample));
            short pcm = (short) (sample * 32767);
            byteBuffer[idx++] = (byte) (pcm & 0xff);
            byteBuffer[idx++] = (byte) ((pcm >> 8) & 0xff);
        }

        AudioFormat format = new AudioFormat(sampleRate, 16, 1, true, false);
        ByteArrayInputStream bais = new ByteArrayInputStream(byteBuffer);
        AudioInputStream ais = new AudioInputStream(bais, format, samples.length);
        AudioSystem.write(ais, AudioFileFormat.Type.WAVE, new File(filename));

        logger.info("Saved WAV to {}", filename);
    }

    public static byte[] toRawBytes(float[] samples) {
        byte[] raw = new byte[samples.length * 4];
        ByteBuffer buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        for (float s : samples) {
            buffer.putFloat(s);
        }
        return raw;
    }

    public static byte[] toWavBytes(float[] samples, int sampleRate) throws Exception {
        byte[] pcm = new byte[samples.length * 2];
        int idx = 0;
        for (float f : samples) {
            short s = (short) Math.max(Math.min(f * 32767, 32767), -32768);
            pcm[idx++] = (byte) (s & 0xff);
            pcm[idx++] = (byte) ((s >> 8) & 0xff);
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        AudioFormat format = new AudioFormat(sampleRate, 16, 1, true, false);
        AudioInputStream ais = new AudioInputStream(
                new ByteArrayInputStream(pcm), format, samples.length);
        AudioSystem.write(ais, AudioFileFormat.Type.WAVE, baos);
        return baos.toByteArray();
    }
}
