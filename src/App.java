import controladores.ControladorReproductor;
import servicios.ServicioDecodificador;
import vistas.VistaReproductor;

public class App {
    public static void main(String[] args) throws Exception {
        javax.swing.SwingUtilities.invokeLater(() -> {
            VistaReproductor vista = new VistaReproductor();
            var servicio = new ServicioDecodificador();
            new ControladorReproductor(vista, servicio);
            vista.setVisible(true);
        });
    }
}
