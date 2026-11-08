package controladores;

import org.bytedeco.javacv.Java2DFrameConverter;

import servicios.ServicioDecodificador;
import servicios.TiempoServicio;
import servicios.Util;
import vistas.VistaReproductor;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import java.awt.image.BufferedImage;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

public class ControladorReproductor {

    private final int TIEMPO_PAUSA = 200; // Muestreo 5 veces por segundo

    private final int TIEMPO_FRAME = 33; // 30 FPS -> 1000 ms/30

    private final VistaReproductor vista;

    private final ServicioDecodificador servicio;

    private long ultimoTiempo;

    // NUEVO: cola intermedia que conecta la etapa DSP con la etapa de reproduccion
    private final BlockingQueue<ServicioDecodificador.ContenedorAudio> colaAudioProcesado =
            new ArrayBlockingQueue<>(ServicioDecodificador.TAMAÑO_MAXIMO_COLA);

    // NUEVO: banderas del ciclo de vida del pipeline de audio
    private volatile boolean dspActivo = false;   // el hilo DSP todavia puede producir
    private volatile boolean audioActivo = false; // el hilo de salida todavia consume

    public ControladorReproductor(VistaReproductor vista,
                                  ServicioDecodificador servicio) {
        this.vista = vista;
        this.servicio = servicio;
        this.vista.setSelecionarClick(evento -> iniciarProceso());
    }

    private void cicloRenderizado() {
        try (var conversor = new Java2DFrameConverter()) {
            while (servicio.isEjecutando()) {
                long inicio = System.currentTimeMillis();
                ServicioDecodificador.ContenedorFrame contenedorARenderizar = servicio.getSiguienteFrame();
                if (contenedorARenderizar != null) {
                    ultimoTiempo = contenedorARenderizar.tiempo();
                    BufferedImage imgRenderizada = conversor.convert(contenedorARenderizar.frame());
                    vista.actualizarImagenVideo(imgRenderizada);
                    contenedorARenderizar.frame().close();
                }
                long tiempoTranscurrido = System.currentTimeMillis() - inicio;
                long tiempoPausa = Math.max(0, TIEMPO_FRAME - tiempoTranscurrido);
                TiempoServicio.pausarMilisegundos(tiempoPausa);
            }
        } catch (Exception ex) {
            System.err.println("[Controlador] Error en el ciclo de renderizado: " + ex.getMessage());
        }
    }

    private void cicloTelemetria() {
        while (servicio.isEjecutando()) {
            ServicioDecodificador.Metricas metricas = servicio.getMetricas(ultimoTiempo);
            vista.actualizarTelemetria(metricas);
            TiempoServicio.pausarMilisegundos(TIEMPO_PAUSA);
        }
    }

    private void iniciarProceso() {
        var archivo = vista.solicitarArchivoVideo();
        if (archivo == null)
            return;

        // Se activan las banderas ANTES de lanzar los hilos para evitar que
        // la etapa final vea "inactivo" al arrancar.
        dspActivo = true;
        audioActivo = true;
        colaAudioProcesado.clear();

        // 1. Hilo PRODUCTOR (decodifica video y audio concurrentemente y los encola)
        new Thread(() -> servicio.iniciarDecodificacion(archivo.getAbsolutePath()), "Hilo Decodificacion").start();

        // 2. Hilo CONSUMIDOR DE VIDEO (muestra las imagenes)
        new Thread(this::cicloRenderizado, "Hilo Renderizado").start();

        // 3. NUEVO: Hilo DSP (etapa 2 del pipeline): colaAudio -> eco + carga CPU -> colaAudioProcesado
        new Thread(this::cicloProcesamientoAudio, "Hilo DSP Audio").start();

        // 4. Hilo CONSUMIDOR DE AUDIO (etapa 3 del pipeline): colaAudioProcesado -> hardware
        new Thread(this::cicloAudio, "Hilo Audio").start();

        // 5. Hilo TELEMETRIA
        new Thread(this::cicloTelemetria, "Hilo Telemetria").start();
    }

    // ETAPA DSP: aqui vive todo el costo matematico (eco + carga artificial)
    private void cicloProcesamientoAudio() {
        try {
            while (true) {
                ServicioDecodificador.ContenedorAudio audioData = servicio.getSiguienteAudio();

                if (audioData == null) {
                    // Cola de entrada vacía: si el productor ya termino, no queda nada por procesar
                    if (!servicio.isEjecutando()) break;
                    Thread.sleep(2);
                    continue;
                }

                if (audioData.samples() == null || audioData.samples().length == 0) continue;

                // Efecto de eco + trampa de CPU (se ejecuta SOLO en este hilo)
                Util.aplicarEfectoEcoSincrono(audioData.samples(), audioData.numChannels());

                // Entrega a la etapa de reproducción con backpressure (cola acotada).
                // Si el hilo de salida murió, se abandona para no quedar bloqueado.
                while (audioActivo && !colaAudioProcesado.offer(audioData, 100, TimeUnit.MILLISECONDS)) {
                    // reintenta mientras la cola esté llena y la salida siga viva
                }
                if (!audioActivo) break;
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (Exception ex) {
            System.err.println("[Controlador] Error en el ciclo de procesamiento de audio: " + ex.getMessage());
        } finally {
            dspActivo = false;
        }
    }

    // ETAPA DE SALIDA: consumidor liviano, solo extrae y escribe en hardware
    private void cicloAudio() {
        SourceDataLine lineaAudio = null;
        try {
            // Sigue vivo mientras el DSP pueda producir o queden bloques procesados por reproducir
            while (dspActivo || !colaAudioProcesado.isEmpty()) {
                ServicioDecodificador.ContenedorAudio audioData =
                        colaAudioProcesado.poll(50, TimeUnit.MILLISECONDS);

                if (audioData == null) continue; // sin datos aun; vuelve a evaluar el ciclo de vida

                int canales = audioData.numChannels();
                float sampleRate = audioData.sampleRate();

                // Configurar la línea de audio la primera vez que se reciben muestras
                if (lineaAudio == null) {
                    AudioFormat formato = new AudioFormat(
                            AudioFormat.Encoding.PCM_SIGNED,
                            sampleRate,
                            16, // 16 bits por muestra
                            canales,
                            canales * 2, // Tamaño de frame (bytes por muestra * canales)
                            sampleRate,
                            false // Little-endian
                    );
                    lineaAudio = AudioSystem.getSourceDataLine(formato);
                    lineaAudio.open(formato);
                    lineaAudio.start();
                }

                // Solo conversion a bytes PCM (sin efectos ni retardos) y escritura al hardware
                byte[] bytesAudio = Util.convertirMuestrasABytesPCM(audioData.samples(), canales);
                if (bytesAudio != null && bytesAudio.length > 0) {
                    lineaAudio.write(bytesAudio, 0, bytesAudio.length);
                }
            }
        } catch (Exception ex) {
            System.err.println("[Controlador] Error en el ciclo de audio: " + ex.getMessage());
        } finally {
            audioActivo = false;
            if (lineaAudio != null) {
                lineaAudio.drain();
                lineaAudio.stop();
                lineaAudio.close();
            }
        }
    }
}