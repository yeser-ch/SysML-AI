package com.schroedingersblock.installer;

import com.formdev.flatlaf.FlatDarkLaf;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.nio.file.Files;
import java.util.Objects;

class InstallerMain extends JFrame {

    private static final String APP_NAME = "Schrödinger's Block – Installer";
    private static final String PLUGIN_DIR_NAME = "com.schroedingersblock";
    private final boolean isMac;

    // UI Elemente
    private CardLayout cardLayout;
    private JPanel mainPanel;
    private JTextField txtPluginsPath;
    private JTextArea txtLog;

    // Daten für die Installation
    private File pluginsDir;

    public InstallerMain() {
        String os = System.getProperty("os.name").toLowerCase();
        isMac = os.contains("mac");
        initUI();
    }

    private void initUI() {
        setTitle(APP_NAME);
        setSize(750, 500);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLocationRelativeTo(null);

        try {
            ImageIcon icon = new ImageIcon(Objects.requireNonNull(getClass().getResource("/images/logo.png")));
            setIconImage(icon.getImage());
            if (isMac) {
                Taskbar.getTaskbar().setIconImage(icon.getImage());
            }
        } catch (Exception e) {
            System.out.println("Logo nicht gefunden.");
        }

        cardLayout = new CardLayout();
        mainPanel = new JPanel(cardLayout);
        mainPanel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

        mainPanel.add(createWelcomePanel(), "WELCOME");
        mainPanel.add(createPathPanel(), "PATHS");
        mainPanel.add(createInstallPanel(), "INSTALL");

        add(mainPanel);
    }

    // --- GUI PANELS ---

    private JPanel createWelcomePanel() {
        JPanel p = new JPanel(new BorderLayout(0, 20));

        JLabel logoLabel = new JLabel();
        logoLabel.setHorizontalAlignment(SwingConstants.CENTER);
        try {
            ImageIcon originalIcon = new ImageIcon(Objects.requireNonNull(getClass().getResource("/images/logo.png")));
            Image img = originalIcon.getImage();
            int width = 200;
            int height = (int) ((double) img.getHeight(null) / img.getWidth(null) * width);
            if (height > 200) {
                height = 200;
                width = (int) ((double) img.getWidth(null) / img.getHeight(null) * height);
            }
            Image scaledImg = img.getScaledInstance(width, height, Image.SCALE_SMOOTH);
            logoLabel.setIcon(new ImageIcon(scaledImg));
        } catch (Exception e) {
            logoLabel.setText("[LOGO]");
        }

        JLabel t = new JLabel("Willkommen beim Installer für Schrödinger's Block", SwingConstants.CENTER);
        t.setFont(new Font("SansSerif", Font.BOLD, 22));

        JLabel i = new JLabel("<html><center>Dieses Tool installiert das Schrödinger's Block Plugin für MagicDraw / Cameo Systems Modeler.<br><br>" +
                "Erkanntes System: <b>" + (isMac ? "macOS" : "Windows") + "</b><br>" +
                "Bitte stellen Sie sicher, dass MagicDraw geschlossen ist.</center></html>", SwingConstants.CENTER);
        i.setFont(new Font("SansSerif", Font.PLAIN, 14));

        JPanel centerPanel = new JPanel();
        centerPanel.setLayout(new BoxLayout(centerPanel, BoxLayout.Y_AXIS));
        logoLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        t.setAlignmentX(Component.CENTER_ALIGNMENT);
        i.setAlignmentX(Component.CENTER_ALIGNMENT);

        centerPanel.add(logoLabel);
        centerPanel.add(Box.createVerticalStrut(20));
        centerPanel.add(t);
        centerPanel.add(Box.createVerticalStrut(10));
        centerPanel.add(i);

        JButton b = new JButton("Installation starten ➔");
        b.setFont(new Font("SansSerif", Font.BOLD, 14));
        b.addActionListener(e -> cardLayout.show(mainPanel, "PATHS"));

        p.add(centerPanel, BorderLayout.CENTER);
        p.add(b, BorderLayout.SOUTH);
        return p;
    }

    private JPanel createPathPanel() {
        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(5, 5, 5, 5);
        g.fill = GridBagConstraints.HORIZONTAL;

        g.gridx = 0; g.gridy = 0; g.gridwidth = 2;
        JLabel lblInfo = new JLabel("<html><b>Plugin-Ordner wählen:</b><br>" +
                "Bitte wählen Sie den <code>plugins</code>-Ordner im Installationsverzeichnis von MagicDraw / Cameo Systems Modeler.<br>" +
                "<i style='color:gray'>Beispiel macOS: /Applications/MagicDraw.app/Contents/plugins<br>" +
                "Beispiel Windows: C:\\Program Files\\MagicDraw\\plugins</i></html>");
        p.add(lblInfo, g);

        g.gridy++; g.gridwidth = 1; g.weightx = 1.0;
        txtPluginsPath = new JTextField();
        p.add(txtPluginsPath, g);

        g.gridx = 1; g.weightx = 0.0;
        JButton btnBrowse = new JButton("Suchen...");
        btnBrowse.addActionListener(e -> chooseDir(txtPluginsPath));
        p.add(btnBrowse, g);

        JButton next = new JButton("Jetzt Installieren ➔");
        next.setFont(new Font("SansSerif", Font.BOLD, 14));
        next.addActionListener(e -> {
            if (txtPluginsPath.getText().isBlank()) {
                JOptionPane.showMessageDialog(this, "Bitte den plugins-Ordner angeben.");
                return;
            }
            File chosen = new File(txtPluginsPath.getText());
            if (!chosen.isDirectory()) {
                JOptionPane.showMessageDialog(this, "Der angegebene Pfad ist kein gültiger Ordner.");
                return;
            }
            pluginsDir = chosen;
            cardLayout.show(mainPanel, "INSTALL");
            startInstallation();
        });
        g.gridx = 0; g.gridy++; g.gridwidth = 2; g.weighty = 1.0; g.anchor = GridBagConstraints.SOUTH;
        p.add(next, g);
        return p;
    }

    private JPanel createInstallPanel() {
        JPanel p = new JPanel(new BorderLayout());
        p.add(new JLabel("Installation läuft...", SwingConstants.CENTER), BorderLayout.NORTH);
        txtLog = new JTextArea();
        txtLog.setEditable(false);
        txtLog.setFont(new Font("Monospaced", Font.PLAIN, 12));
        txtLog.setBackground(new Color(40, 44, 52));
        txtLog.setForeground(new Color(171, 178, 191));
        p.add(new JScrollPane(txtLog), BorderLayout.CENTER);
        return p;
    }

    // --- LOGIC ---

    private void chooseDir(JTextField f) {
        JFileChooser c = new JFileChooser();
        c.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (!f.getText().isBlank()) c.setCurrentDirectory(new File(f.getText()));
        if (c.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) f.setText(c.getSelectedFile().getAbsolutePath());
    }

    private void log(String m) {
        SwingUtilities.invokeLater(() -> {
            txtLog.append(m + "\n");
            txtLog.setCaretPosition(txtLog.getDocument().getLength());
        });
    }

    private void startInstallation() {
        new Thread(() -> {
            try {
                log("--- Start Installation ---");

                // Plugin-Unterordner anlegen: plugins/com.schroedingersblock.plugin/
                File myPluginDir = new File(pluginsDir, PLUGIN_DIR_NAME);
                if (!myPluginDir.exists()) myPluginDir.mkdirs();
                log("Plugin-Verzeichnis: " + myPluginDir.getAbsolutePath());

                // Kern-Dateien kopieren
                copyRes("plugin.xml", new File(myPluginDir, "plugin.xml"));
                copyRes("schroedingers-block.jar", new File(myPluginDir, "schroedingers-block.jar"));

                // data_extraction-Unterordner mit Velocity-Templates anlegen
                File dataExtractionDir = new File(myPluginDir, "data_extraction");
                if (!dataExtractionDir.exists()) dataExtractionDir.mkdirs();
                log("data_extraction-Verzeichnis: " + dataExtractionDir.getAbsolutePath());

                copyRes("sysml_flat_export.vm", new File(dataExtractionDir, "sysml_flat_export.vm"));
                copyRes("sysml_partial_export.vm", new File(dataExtractionDir, "sysml_partial_export.vm"));

                log("--- Installation erfolgreich! ---");
                log("Bitte starten Sie MagicDraw neu.");

                SwingUtilities.invokeLater(() -> {
                    setTitle(APP_NAME + " – Fertig");
                    JOptionPane.showMessageDialog(this, "Installation erfolgreich!\nSie können dieses Fenster nun schließen.");
                    log(">> FERTIG. Fenster kann geschlossen werden. <<");
                });

            } catch (Exception e) {
                log("FEHLER: " + e.getMessage());
                e.printStackTrace();
            }
        }).start();
    }

    private void copyRes(String name, File dest) throws Exception {
        log("Kopiere " + name + "...");
        try (java.io.InputStream is = getClass().getResourceAsStream("/" + name)) {
            if (is == null) throw new Exception("Ressource fehlt im Installer-JAR: " + name);
            Files.copy(is, dest.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static void main(String[] args) {
        try { FlatDarkLaf.setup(); } catch (Exception e) {}
        SwingUtilities.invokeLater(() -> new InstallerMain().setVisible(true));
    }
}