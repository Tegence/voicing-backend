package org.example.voicingbackend.util;

import javax.sound.sampled.*;
import java.io.*;

public class AudioPlayer {

    public static void saveWav(float[] samples, int sampleRate, String filename) throws Exception {

        byte[] byteBuffer = new byte[samples.length * 2];

        int idx = 0;

        for (float sample : samples) {

            // Clamp to prevent distortion
            sample = Math.max(-1.0f, Math.min(1.0f, sample));

            short pcm = (short) (sample * 32767);

            byteBuffer[idx++] = (byte) (pcm & 0xff);
            byteBuffer[idx++] = (byte) ((pcm >> 8) & 0xff);
        }

        AudioFormat format = new AudioFormat(
                sampleRate,
                16,
                1,
                true,
                false
        );

        ByteArrayInputStream bais = new ByteArrayInputStream(byteBuffer);

        AudioInputStream ais = new AudioInputStream(
                bais,
                format,
                samples.length
        );

        AudioSystem.write(
                ais,
                AudioFileFormat.Type.WAVE,
                new File(filename)
        );

        System.out.println("Saved to " + filename);
    }
}
