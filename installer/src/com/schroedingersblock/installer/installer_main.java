package com.schroedingersblock.installer;

import com.formdev.flatlaf.FlatDarkLaf;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.nio.file.Files;
import java.util.Objects;

public class installer_main extends JFrame {

    private static final String APP_NAME = "Schrödinger's Block – Installer";
    private static final String PLUGIN_DIR_NAME_WIN = "com.schroedingersblock.plugin";
    private static final String PLUGIN_DIR_NAME_MAC = "com.schroedingersblock";
    private final boolean isMac;

    // 1. ANPASSUNG: Standardsprache ist jetzt Englisch
    private String lang = "EN";

    // UI Elemente
    private CardLayout cardLayout;
    private JPanel mainPanel;
    private JTextField txtPluginsPath;
    private JTextArea txtLog;
    private JLabel lblWelcomeTitle;
    private JLabel lblWelcomeInfo;
    private JButton btnStart;
    private JLabel lblPathInfo;
    private JButton btnBrowse;
    private JButton btnInstall;
    private JLabel lblInstallRunning;

    private File pluginsDir;

    public installer_main() {
        String os = System.getProperty("os.name").toLowerCase();
        isMac = os.contains("mac");
        initUI();
        guessDefaultPath();
    }

    private void initUI() {
        setTitle(APP_NAME);
        setSize(750, 500);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLocationRelativeTo(null);

        // --- Kopfzeilen-Icon ---
        try {
            // HINWEIS: Wenn dein Bild im Ordner 'images' anders heißt, ersetze 'logo.png' durch deinen Dateinamen!
            java.net.URL iconURL = getClass().getResource("/images/SchroedingersBlock_Logo.png");
            if (iconURL != null) {
                ImageIcon icon = new ImageIcon(iconURL);
                setIconImage(icon.getImage());
                if (isMac) {
                    Taskbar.getTaskbar().setIconImage(icon.getImage());
                }
            }
        } catch (Exception e) {
            System.out.println("Kopfzeilen-Icon konnte nicht geladen werden.");
        }

        // --- Sprachauswahl (Oben rechts) ---
        JPanel headerPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        String[] languages = {"English", "Deutsch"};
        JComboBox<String> langBox = new JComboBox<>(languages);
        langBox.setSelectedItem("English");
        langBox.addActionListener(e -> {
            if ("English".equals(langBox.getSelectedItem())) {
                lang = "EN";
            } else {
                lang = "DE";
            }
            updateTexts();
        });
        headerPanel.add(new JLabel("🌍 Language: "));
        headerPanel.add(langBox);
        add(headerPanel, BorderLayout.NORTH);

        // --- Haupt-Panel mit CardLayout ---
        cardLayout = new CardLayout();
        mainPanel = new JPanel(cardLayout);
        mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 20, 20, 20));

        mainPanel.add(createWelcomePanel(), "WELCOME");
        mainPanel.add(createPathPanel(), "PATHS");
        mainPanel.add(createInstallPanel(), "INSTALL");

        add(mainPanel, BorderLayout.CENTER);

        updateTexts();
    }

    private void guessDefaultPath() {
        if (isMac) {
            txtPluginsPath.setText("/Applications/Cameo Systems Modeler/plugins");
        } else {
            // Baut dynamisch das Windows-Standardverzeichnis vorab ein
            String userHome = System.getProperty("user.home");
            File defaultWinPath = new File(userHome, "AppData\\Local\\.magic.systems.of.systems.architect\\2026x\\plugins");
            txtPluginsPath.setText(defaultWinPath.getAbsolutePath());
        }
    }

    // --- GUI PANELS ---

    private JPanel createWelcomePanel() {
        JPanel p = new JPanel(new BorderLayout(0, 20));

        JLabel logoLabel = new JLabel();
        logoLabel.setHorizontalAlignment(SwingConstants.CENTER);

        // --- 2. ANPASSUNG: Sicherer Fix für das große Logo im Startbildschirm ---
        try {
            // HINWEIS: Wenn dein Bild im Ordner 'images' anders heißt, ersetze 'logo.png' durch deinen Dateinamen!
            java.net.URL imgURL = getClass().getResource("/images/SchroedingersBlock_Logo.png");
            if (imgURL != null) {
                ImageIcon originalIcon = new ImageIcon(imgURL);

                // Wir lesen die Größe direkt und synchron aus dem ImageIcon, um den Swing-Ladefehler (-1) zu verhindern
                int origW = originalIcon.getIconWidth();
                int origH = originalIcon.getIconHeight();

                int width = 200;
                int height = (int) ((double) origH / origW * width);
                if (height > 200) {
                    height = 200;
                    width = (int) ((double) origW / origH * height);
                }

                Image img = originalIcon.getImage();
                Image scaledImg = img.getScaledInstance(width, height, Image.SCALE_SMOOTH);
                logoLabel.setIcon(new ImageIcon(scaledImg));
            } else {
                logoLabel.setText("[BILD-RESSOURCE NICHT GEFUNDEN]");
            }
        } catch (Exception e) {
            logoLabel.setText("[LOGO LADEFEHLER]");
        }

        lblWelcomeTitle = new JLabel("", SwingConstants.CENTER);
        lblWelcomeTitle.setFont(new Font("SansSerif", Font.BOLD, 22));

        lblWelcomeInfo = new JLabel("", SwingConstants.CENTER);
        lblWelcomeInfo.setFont(new Font("SansSerif", Font.PLAIN, 14));

        JPanel centerPanel = new JPanel();
        centerPanel.setLayout(new BoxLayout(centerPanel, BoxLayout.Y_AXIS));
        logoLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        lblWelcomeTitle.setAlignmentX(Component.CENTER_ALIGNMENT);
        lblWelcomeInfo.setAlignmentX(Component.CENTER_ALIGNMENT);

        centerPanel.add(logoLabel);
        centerPanel.add(Box.createVerticalStrut(20));
        centerPanel.add(lblWelcomeTitle);
        centerPanel.add(Box.createVerticalStrut(10));
        centerPanel.add(lblWelcomeInfo);

        btnStart = new JButton();
        btnStart.setFont(new Font("SansSerif", Font.BOLD, 14));
        btnStart.addActionListener(e -> cardLayout.show(mainPanel, "PATHS"));

        p.add(centerPanel, BorderLayout.CENTER);
        p.add(btnStart, BorderLayout.SOUTH);
        return p;
    }

    private JPanel createPathPanel() {
        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(5, 5, 5, 5);
        g.fill = GridBagConstraints.HORIZONTAL;

        g.gridx = 0; g.gridy = 0; g.gridwidth = 2;
        lblPathInfo = new JLabel();
        p.add(lblPathInfo, g);

        g.gridy++; g.gridwidth = 1; g.weightx = 1.0;
        txtPluginsPath = new JTextField();
        p.add(txtPluginsPath, g);

        g.gridx = 1; g.weightx = 0.0;
        btnBrowse = new JButton();
        btnBrowse.addActionListener(e -> chooseDir(txtPluginsPath));
        p.add(btnBrowse, g);

        btnInstall = new JButton();
        btnInstall.setFont(new Font("SansSerif", Font.BOLD, 14));
        btnInstall.addActionListener(e -> {
            if (txtPluginsPath.getText().isBlank()) {
                JOptionPane.showMessageDialog(this, getT("ERR_EMPTY"));
                return;
            }
            File chosen = new File(txtPluginsPath.getText());
            if (!chosen.isDirectory()) {
                JOptionPane.showMessageDialog(this, getT("ERR_INVALID"));
                return;
            }
            pluginsDir = chosen;
            cardLayout.show(mainPanel, "INSTALL");
            startInstallation();
        });
        g.gridx = 0; g.gridy++; g.gridwidth = 2; g.weighty = 1.0; g.anchor = GridBagConstraints.SOUTH;
        p.add(btnInstall, g);
        return p;
    }

    private JPanel createInstallPanel() {
        JPanel p = new JPanel(new BorderLayout());
        lblInstallRunning = new JLabel("", SwingConstants.CENTER);
        p.add(lblInstallRunning, BorderLayout.NORTH);
        txtLog = new JTextArea();
        txtLog.setEditable(false);
        txtLog.setFont(new Font("Monospaced", Font.PLAIN, 12));
        txtLog.setBackground(new Color(40, 44, 52));
        txtLog.setForeground(new Color(171, 178, 191));
        p.add(new JScrollPane(txtLog), BorderLayout.CENTER);
        return p;
    }

    // --- TEXTE & ÜBERSETZUNGEN ---

    private void updateTexts() {
        lblWelcomeTitle.setText(getT("WELCOME_TITLE"));
        lblWelcomeInfo.setText(getT("WELCOME_INFO"));
        btnStart.setText(getT("BTN_START"));
        lblPathInfo.setText(getT("PATH_INFO"));
        btnBrowse.setText(getT("BTN_BROWSE"));
        btnInstall.setText(getT("BTN_INSTALL"));
        lblInstallRunning.setText(getT("INSTALL_RUNNING"));
    }

    private String getT(String key) {
        boolean isEn = "EN".equals(lang);
        String sysName = isMac ? "macOS" : "Windows";

        switch (key) {
            case "WELCOME_TITLE":
                return isEn ? "Welcome to the Schrödinger's Block Installer" : "Willkommen beim Installer für Schrödinger's Block";
            case "WELCOME_INFO":
                return isEn ? "<html><center>This tool installs the Schrödinger's Block Plugin for MagicDraw / Cameo.<br><br>Detected System: <b>" + sysName + "</b><br>Please ensure that CATIA Magic Systems of Systems Architect is closed.</center></html>"
                        : "<html><center>Dieses Tool installiert das Schrödinger's Block Plugin für MagicDraw / Cameo.<br><br>Erkanntes System: <b>" + sysName + "</b><br>Bitte stellen Sie sicher, dass das CATIA Magic Systems of Systems Architect geschlossen ist.</center></html>";
            case "BTN_START":
                return isEn ? "Start Installation ➔" : "Installation starten ➔";
            case "PATH_INFO":
                // 3. ANPASSUNG: Anonymisiertes, generisches Pfadbeispiel für Windows mit <username>
                String winExample = "C:\\Users\\&lt;username&gt;\\AppData\\Local\\.magic.systems.of.systems.architect\\2026x\\plugins";
                String macExample = "/Applications/Cameo Systems Modeler/plugins";

                if (isEn) {
                    return "<html><b>Select Plugin Folder:</b><br>Please select the <code>plugins</code> folder in your installation directory.<br><br>" +
                            "<i style='color:gray'>Example Windows: " + winExample + "<br>" +
                            "Example macOS: " + macExample + "</i></html>";
                } else {
                    return "<html><b>Plugin-Ordner wählen:</b><br>Bitte wählen Sie den <code>plugins</code>-Ordner im Installationsverzeichnis.<br><br>" +
                            "<i style='color:gray'>Beispiel Windows: " + winExample + "<br>" +
                            "Beispiel macOS: " + macExample + "</i></html>";
                }
            case "BTN_BROWSE":
                return isEn ? "Browse..." : "Suchen...";
            case "BTN_INSTALL":
                return isEn ? "Install Now ➔" : "Jetzt Installieren ➔";
            case "INSTALL_RUNNING":
                return isEn ? "Installation in progress..." : "Installation läuft...";
            case "ERR_EMPTY":
                return isEn ? "Please specify the plugins folder." : "Bitte den plugins-Ordner angeben.";
            case "ERR_INVALID":
                return isEn ? "The specified path is not a valid folder." : "Der angegebene Pfad ist kein gültiger Ordner.";
            case "SUCCESS":
                return isEn ? "Installation successful!\nYou can now close this window." : "Installation erfolgreich!\nSie können dieses Fenster nun schließen.";
            default:
                return key;
        }
    }

    // --- LOGIK & DATEI-OPERATIONEN ---

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
                log(lang.equals("EN") ? "--- Starting Installation ---" : "--- Start Installation ---");

                String pluginDirName = isMac ? PLUGIN_DIR_NAME_MAC : PLUGIN_DIR_NAME_WIN;
    
                File myPluginDir = new File(pluginsDir, pluginDirName);
                if (!myPluginDir.exists()) myPluginDir.mkdirs();
                log((lang.equals("EN") ? "Plugin directory: " : "Plugin-Verzeichnis: ") + myPluginDir.getAbsolutePath());

                copyRes("plugin.xml", new File(myPluginDir, "plugin.xml"));
                copyRes("schroedingers-block.jar", new File(myPluginDir, "schroedingers-block.jar"));

                File dataExtractionDir = new File(myPluginDir, "data_extraction");
                if (!dataExtractionDir.exists()) dataExtractionDir.mkdirs();

                copyRes("sysml_flat_export.vm", new File(dataExtractionDir, "sysml_flat_export.vm"));
                copyRes("sysml_partial_export.vm", new File(dataExtractionDir, "sysml_partial_export.vm"));

                File promptsDir = new File(myPluginDir, "prompts");
                if (!promptsDir.exists()) promptsDir.mkdirs();

                copyRes("basic_prompt.txt", new File(promptsDir, "basic_prompt.txt"));
                copyRes("expert_prompt.txt", new File(promptsDir, "expert_prompt.txt"));

                log(lang.equals("EN") ? "--- Installation successful! ---" : "--- Installation erfolgreich! ---");

                SwingUtilities.invokeLater(() -> {
                    setTitle(APP_NAME + (lang.equals("EN") ? " – Done" : " – Fertig"));
                    JOptionPane.showMessageDialog(this, getT("SUCCESS"));
                });

            } catch (Exception e) {
                log((lang.equals("EN") ? "ERROR: " : "FEHLER: ") + e.getMessage());
                e.printStackTrace();
            }
        }).start();
    }

    private void copyRes(String name, File dest) throws Exception {
        log((lang.equals("EN") ? "Copying " : "Kopiere ") + name + "...");
        try (java.io.InputStream is = getClass().getResourceAsStream("/" + name)) {
            if (is == null) throw new Exception((lang.equals("EN") ? "Missing resource in JAR: " : "Ressource fehlt im Installer-JAR: ") + name);
            Files.copy(is, dest.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static void main(String[] args) {
        try { FlatDarkLaf.setup(); } catch (Exception e) {}
        SwingUtilities.invokeLater(() -> new installer_main().setVisible(true));
    }
}