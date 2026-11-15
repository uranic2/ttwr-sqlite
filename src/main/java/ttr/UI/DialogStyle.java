package ttr.UI;

import java.awt.*;
import javax.swing.*;

/** Shared dialog geometry, in logical units relative to the application font. */
public final class DialogStyle {
    private DialogStyle() {}
    public static float scale() { return baseFont().getSize2D() / 12f; }
    private static Font baseFont() {
        Font font = UIMan.font12;
        return font == null ? UIManager.getFont("Label.font") : font;
    }
    public static int size(int value) { return Math.round(value * scale()); }
    public static void fonts(Component component) {
        Font font = component.getFont();
        if (font != null) {
            int extra = component instanceof JComponent && ((JComponent) component).getClientProperty("dialog-font-extra") instanceof Integer
                    ? (Integer) ((JComponent) component).getClientProperty("dialog-font-extra") : 0;
            component.setFont(baseFont().deriveFont(font.getStyle(), baseFont().getSize2D() + extra));
        }
        if (component instanceof Container) for (Component child : ((Container) component).getComponents()) fonts(child);
    }
    public static void heading(JLabel label, int extra) {
        label.putClientProperty("dialog-font-extra", extra);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
    }
    public static void contentBorder(JPanel panel) {
        panel.setBorder(BorderFactory.createEmptyBorder(size(12), size(16), size(12), size(16)));
    }
    public static JPanel actions(String name, JButton... buttons) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT, size(8), 0));
        panel.setName(name);
        Color color = UIManager.getColor("nimbusBorder");
        if (color == null) color = UIManager.getColor("Separator.foreground");
        if (color == null) color = UIManager.getColor("controlDkShadow");
        if (color == null) color = panel.getForeground();
        // FlowLayout contributes one horizontal gap at each outer edge.
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(Math.max(1, size(1)), 0, 0, 0, color),
                BorderFactory.createEmptyBorder(size(8), size(16) - size(8), size(8), size(16) - size(8))));
        for (JButton button : buttons) {
            fonts(button); panel.add(button);
            button.addPropertyChangeListener("text", event -> {
                sizeActions(panel);
                SwingUtilities.invokeLater(() -> {
                    Window window = SwingUtilities.getWindowAncestor(panel);
                    if (window != null && window.isShowing()) window.pack();
                });
            });
        }
        sizeActions(panel);
        return panel;
    }
    private static void sizeActions(JPanel panel) {
        int width = size(112), height = size(32);
        for (Component child : panel.getComponents()) {
            JButton button = (JButton) child;
            Dimension preferred = button.getUI().getPreferredSize(button);
            width = Math.max(width, preferred.width); height = Math.max(height, preferred.height);
        }
        for (Component child : panel.getComponents()) child.setPreferredSize(new Dimension(width, height));
        panel.revalidate();
    }
}
