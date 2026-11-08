```mermaid
graph TD
    %% Definición de Estilos
    classDef vista fill:#e1f5fe,stroke:#0288d1,stroke-width:2px,color:#01579b;
    classDef controlador fill:#fff3e0,stroke:#f57c00,stroke-width:2px,color:#e65100;
    classDef servicio fill:#e8f5e9,stroke:#388e3c,stroke-width:2px,color:#1b5e20;
    classDef hardware fill:#f3e5f5,stroke:#7b1fa2,stroke-width:2px,color:#4a148c;

    subgraph PRESENTACION ["Capa de Presentación (GUI)"]
        VISTA["<b>VistaReproductor</b><br/><i>(Swing / JFrame)</i><br/>- PanelVideo (Canvas)<br/>- Panel Telemetría (RAM, CPU, I/O)"]:::vista
    end

    subgraph CONTROL ["Capa de Control (Orquestación)"]
        CTRL["<b>ControladorReproductor</b><br/><i>(Mediador)</i><br/>- Coordina Hilos de Ejecución<br/>- Controla los 30 FPS (~33ms)"]:::controlador
    end

    subgraph SERVICIO ["Capa de Servicio (Lógica y Métricas)"]
        SERV["<b>ServicioDecodificador</b><br/><i>(JavaCV / FFmpeg)</i><br/>- Muestreo OSMXBean (CPU/RAM)<br/>- Decodificación en C++"]:::servicio
        BUFFER[("<b>Ring Buffer</b><br/><i>BlockingQueue (Máx 30)</i><br/>Backpressure")]:::servicio
    end

    subgraph RECURSOS ["Hardware y Sistema Operativo"]
        OFFHEAP["<b>Off-Heap / Memoria Nativa</b><br/><i>(Frames Raw / Punteros C++)</i>"]:::hardware
        DISK["<b>Disco Duro / SSD</b><br/><i>(Archivo MP4 ~500 MB)</i>"]:::hardware
    end

    %% Secuencia Unificada y Ordenada (1 al 10)
    VISTA -- "1. Usuario hace clic en 'Abrir MP4'" --> CTRL
    CTRL -- "2. Ordena iniciar lectura y decodificación" --> SERV
    
    SERV -- "3. Hilo 1 (Productor): Lee paquetes del disco" --> DISK
    SERV -- "4. Descomprime y asigna el frame en RAM C++" --> OFFHEAP
    SERV -- "5. Deposita el frame en la cola" --> BUFFER

    BUFFER -- "6. Hilo 2 (Consumidor): Extrae frame cada 33ms" --> CTRL
    CTRL -- "7. Convierte y dibuja la imagen en el Canvas" --> VISTA
    CTRL -- "8. Invoca frame.close() para liberar la RAM C++" --> OFFHEAP

    CTRL -- "9. Hilo 3 (Telemetría): Pide métricas de CPU/RAM" --> SERV
    SERV -- "10. Envía paquete MetricasHardware para actualizar la GUI" --> VISTA

    %% Clases
    class VISTA vista;
    class CTRL controlador;
    class SERV,BUFFER servicio;
    class OFFHEAP,DISK hardware;