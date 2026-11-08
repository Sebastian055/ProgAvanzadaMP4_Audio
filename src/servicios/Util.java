package servicios;

import java.nio.Buffer;
import java.nio.FloatBuffer;
import java.util.LinkedList;
import java.util.Queue;

public class Util {

    // Método para duplicar de forma segura cualquier tipo de Buffer de JavaCV
    public static Buffer duplicarBuffer(Buffer original) {
        if (original instanceof java.nio.FloatBuffer) {
            java.nio.FloatBuffer fb = (java.nio.FloatBuffer) original;
            java.nio.FloatBuffer clone = java.nio.FloatBuffer.allocate(fb.capacity());
            fb.mark();
            clone.put(fb);
            fb.reset();
            clone.flip();
            return clone;
        } else if (original instanceof java.nio.ShortBuffer) {
            java.nio.ShortBuffer sb = (java.nio.ShortBuffer) original;
            java.nio.ShortBuffer clone = java.nio.ShortBuffer.allocate(sb.capacity());
            sb.mark();
            clone.put(sb);
            sb.reset();
            clone.flip();
            return clone;
        } else if (original instanceof java.nio.ByteBuffer) {
            java.nio.ByteBuffer bb = (java.nio.ByteBuffer) original;
            java.nio.ByteBuffer clone = java.nio.ByteBuffer.allocate(bb.capacity());
            bb.mark();
            clone.put(bb);
            bb.reset();
            clone.flip();
            return clone;
        }
        return original; // Fallback si fuera otro tipo
    }

    // Conversor robusto que maneja tanto Mono como Estéreo (Planares y Empaquetados) sin distorsión
    public static byte[] convertirMuestrasABytesPCM(java.nio.Buffer[] buffers, int canales) {
        if (buffers == null || buffers.length == 0) return new byte[0];

        java.nio.Buffer bufferPrincipal = buffers[0];

        // 1. Si el búfer es de tipo FloatBuffer (El formato más común que entrega FFmpeg)
        if (bufferPrincipal instanceof java.nio.FloatBuffer) {
            java.nio.FloatBuffer left = (java.nio.FloatBuffer) bufferPrincipal;
            left.rewind();

            // Si es estéreo y tenemos búferes separados para izquierdo y derecho (Planar)
            if (canales == 2 && buffers.length >= 2 && buffers[1] instanceof java.nio.FloatBuffer) {
                java.nio.FloatBuffer right = (java.nio.FloatBuffer) buffers[1];
                right.rewind();

                int length = Math.min(left.remaining(), right.remaining());
                byte[] bytes = new byte[length * 4]; // length * 2 canales * 2 bytes por short
                int idx = 0;

                while (left.hasRemaining() && right.hasRemaining()) {
                    // Limitar los valores flotantes estrictamente entre -1.0 y 1.0 para evitar clipping metálico
                    float fL = Math.max(-1.0f, Math.min(1.0f, left.get()));
                    float fR = Math.max(-1.0f, Math.min(1.0f, right.get()));

                    short sL = (short) (fL * 32767.0f);
                    short sR = (short) (fR * 32767.0f);

                    // Entrelazar canales: Izquierdo (Low/High), Derecho (Low/High)
                    bytes[idx++] = (byte) (sL & 0xff);
                    bytes[idx++] = (byte) ((sL >> 8) & 0xff);
                    bytes[idx++] = (byte) (sR & 0xff);
                    bytes[idx++] = (byte) ((sR >> 8) & 0xff);
                }
                return bytes;
            } else {
                // Caso Mono en FloatBuffer
                left.rewind();
                byte[] bytes = new byte[left.remaining() * 2];
                int idx = 0;
                while (left.hasRemaining()) {
                    float f = Math.max(-1.0f, Math.min(1.0f, left.get()));
                    short s = (short) (f * 32767.0f);
                    bytes[idx++] = (byte) (s & 0xff);
                    bytes[idx++] = (byte) ((s >> 8) & 0xff);
                }
                return bytes;
            }
        }

        // 2. Si el búfer ya viene como ShortBuffer directo
        else if (bufferPrincipal instanceof java.nio.ShortBuffer) {
            java.nio.ShortBuffer sb = (java.nio.ShortBuffer) bufferPrincipal;
            sb.rewind();
            byte[] bytes = new byte[sb.remaining() * 2];
            int idx = 0;
            while (sb.hasRemaining()) {
                short s = sb.get();
                bytes[idx++] = (byte) (s & 0xff);
                bytes[idx++] = (byte) ((s >> 8) & 0xff);
            }
            return bytes;
        }

        return new byte[0];
    }


    // Estructuras añadidas para simular el procesamiento DSP pesado (Efecto Eco)
    private static final Queue<float[]> historialEco = new LinkedList<>();
    private static final int RETARDO_ECO = 5;

    // Método que aplica el eco de forma síncrona y añade la trampa de CPU
    public static void aplicarEfectoEcoSincrono(Buffer[] buffers, int canales) {
        if (buffers == null || buffers.length == 0) return;

        Buffer bufferPrincipal = buffers[0];

        // 1. Si el búfer es FloatBuffer
        if (bufferPrincipal instanceof FloatBuffer) {
            FloatBuffer fb = (FloatBuffer) bufferPrincipal;
            fb.rewind();
            float[] muestras = new float[fb.remaining()];
            fb.get(muestras);

            historialEco.add(muestras.clone());
            if (historialEco.size() > RETARDO_ECO) {
                float[] muestrasAntiguas = historialEco.poll();
                for (int i = 0; i < muestras.length && i < muestrasAntiguas.length; i++) {
                    muestras[i] = (muestras[i] * 0.5f) + (muestrasAntiguas[i] * 0.5f);
                }
            }

            fb.rewind();
            fb.put(muestras);
            fb.rewind();
        }
        // 2. Si el búfer es ShortBuffer (Muy común en varios formatos MP4)
        else if (bufferPrincipal instanceof java.nio.ShortBuffer) {
            java.nio.ShortBuffer sb = (java.nio.ShortBuffer) bufferPrincipal;
            sb.rewind();
            float[] muestras = new float[sb.remaining()];
            for (int i = 0; i < muestras.length; i++) {
                muestras[i] = sb.get() / 32767.0f; // Normalizar a flotante
            }

            historialEco.add(muestras.clone());
            if (historialEco.size() > RETARDO_ECO) {
                float[] muestrasAntiguas = historialEco.poll();
                for (int i = 0; i < muestras.length && i < muestrasAntiguas.length; i++) {
                    muestras[i] = (muestras[i] * 0.5f) + (muestrasAntiguas[i] * 0.5f);
                }
            }

            sb.rewind();
            for (float f : muestras) {
                short s = (short) (Math.max(-1.0f, Math.min(1.0f, f)) * 32767.0f);
                sb.put(s);
            }
            sb.rewind();
        }

        // ===================================================================
        // TRAMPA PEDAGÓGICA: Forzar el Buffer Underflow
        // ===================================================================

        try {
            // Carga artificial de CPU (cálculo pesado innecesario)
            double dummy = 0;
            for (int j = 0; j < 500000; j++) {
                dummy += Math.sin(j);
            }

            // Pausa larga que excede el límite del buffer de la tarjeta de sonido
            Thread.sleep(50); // Aumentado a 50ms para que la tarjeta de sonido muera de hambre
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }


    }
}
