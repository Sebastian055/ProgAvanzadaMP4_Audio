package vistas;

import java.awt.image.BufferedImage;

import javax.swing.JPanel;

import java.awt.*;

public class PanelVideo extends JPanel {
    private BufferedImage imagenActual;

    public void actualizarImagen(BufferedImage img) {
        this.imagenActual = img;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (imagenActual != null) {
            g.drawImage(imagenActual, 0, 0, getWidth(), getHeight(), null);
        } else {
            g.setColor(Color.BLACK);
            g.fillRect(0, 0, getWidth(), getHeight());
        }
    }
}
