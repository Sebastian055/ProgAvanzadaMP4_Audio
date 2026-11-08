package servicios;

import java.lang.management.ManagementFactory;
import java.nio.Buffer; // Importante para las muestras de audio en JavaCV
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import com.sun.management.OperatingSystemMXBean;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;

public class ServicioDecodificador {

    public static final int TAMAÑO_MAXIMO_COLA = 30;
    private final OperatingSystemMXBean beanSO = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    private boolean ejecutando = false;

    public record ContenedorFrame(Frame frame, long tiempo) {}

    // NUEVO: Contenedor para las muestras de audio
    public record ContenedorAudio(Buffer[] samples, int numChannels, int sampleRate) {}

    public record Metricas(long memoriaHeapMB, long memoriaOffHeapMB,
                           double porcentajeUsoCPU, int ocupacionCola,
                           long tiempoLectura) {
    }

    private final BlockingQueue<ContenedorFrame> colaVideo = new ArrayBlockingQueue<>(TAMAÑO_MAXIMO_COLA);

    // NUEVO: Cola independiente para el flujo de audio
    private final BlockingQueue<ContenedorAudio> colaAudio = new ArrayBlockingQueue<>(TAMAÑO_MAXIMO_COLA);

    public boolean isEjecutando() {
        return ejecutando;
    }

    public void iniciarDecodificacion(String rutaVideo) {
        ejecutando = true;
        try (var grabber = new FFmpegFrameGrabber(rutaVideo)) { // Usando tu instancia de FFmpegFrameGrabber

            grabber.start();

            Frame frameActual;
            while (ejecutando && (frameActual = grabber.grab()) != null) {

                if (frameActual.image != null) {
                    long tiempoInicial = System.nanoTime();
                    Frame frameClonado = frameActual.clone();
                    long tiempo = (System.nanoTime() - tiempoInicial) / 1000000;

                    colaVideo.put(new ContenedorFrame(frameClonado, tiempo));
                }

                if (frameActual.samples != null) {
                    Buffer[] samplesClonados = new Buffer[frameActual.samples.length];
                    for (int i = 0; i < frameActual.samples.length; i++) {
                        samplesClonados[i] = Util.duplicarBuffer(frameActual.samples[i]);
                    }

                    colaAudio.put(new ContenedorAudio(
                            samplesClonados,
                            frameActual.audioChannels,
                            frameActual.sampleRate
                    ));
                }
             }
        } catch (Exception ex) {
            System.err.println("Error en el SERVICIO de Decodificación A/V: " + ex.getMessage());
        } finally {
            ejecutando = false;
        }
    }

    public ContenedorFrame getSiguienteFrame() throws InterruptedException {
        return colaVideo.poll();
    }

    public ContenedorAudio getSiguienteAudio() throws InterruptedException {
        return colaAudio.poll();
    }

    public void detener() {
        this.colaVideo.clear();
        this.colaAudio.clear(); // Limpiar también la cola de audio
        ejecutando = false;
    }

    public Metricas getMetricas(long tiempoLectura) {
        Runtime runtime = Runtime.getRuntime();
        long mb = 1048576;

        long memoriaHeapMB = (runtime.totalMemory() - runtime.freeMemory()) / mb;
        long memoriaProceso = beanSO.getCommittedVirtualMemorySize() / mb;
        long memoriaOffHeapMB = Math.max(0, memoriaProceso - memoriaHeapMB);
        double porcentajeUsoCPU = beanSO.getCpuLoad() * 100;

        // Se reporta el tamaño combinado o el de video para métricas
        return new Metricas(memoriaHeapMB, memoriaOffHeapMB, porcentajeUsoCPU, colaVideo.size(), tiempoLectura);
    }
}