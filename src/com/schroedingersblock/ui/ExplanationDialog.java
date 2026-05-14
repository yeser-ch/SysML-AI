/**
 * Die grafische Benutzeroberfläche und der Chat-Dialog für die Interaktion mit dem Plugin.
 */

package com.schroedingersblock.ui;

import com.schroedingersblock.api.DummyExplanationService;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.NamedElement;
import com.nomagic.magicdraw.core.Application;
import com.nomagic.magicdraw.core.Project;
import com.schroedingersblock.data_extraction.DataExtractionService;
import javax.swing.*;
import java.awt.*;

// Pop-up-Fenster (JFrame statt JDialog für Minimieren-Option)
public class ExplanationDialog extends JFrame {
    private JPanel chatPanel;
    private JScrollPane scrollPane;
    private JTextField inputField;
    private final String mode;
    private final Element targetElement;
    private JButton runBtn;

    // Anbindung der LLM-Logik
    private final DummyExplanationService apiService = new DummyExplanationService();

    public ExplanationDialog(String mode, Element targetElement) {
        this.mode = mode;
        this.targetElement = targetElement;

        setTitle("Schrödinger's Block - SYSML AI Analysis");
        setSize(550, 600);
        setLocationRelativeTo(null);
        setAlwaysOnTop(true);
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout(10, 10));

        buildUI();

        // Sofortige Rückmeldung beim Öffnen
        addMessage("System", "Starting model data export for <b>" + mode + "</b>\u00A0...");

        // Export-Prozess im Hintergrund starten
        runExportTask();
    }

    private void buildUI() {
        // --- Header Panel (Oben) ---
        JPanel headerPanel = new JPanel(new BorderLayout(10, 0));
        headerPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 5, 10));

        // Titel und Target-Information (Oben Links)
        headerPanel.add(createTitleArea(), BorderLayout.WEST);

        // Steuerelemente (Oben Rechts)
        JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        String[] levels = {"Basic", "Expert"};
        JComboBox<String> levelBox = new JComboBox<>(levels);

        // Standardwert aus den Einstellungen laden
        java.util.prefs.Preferences prefs = java.util.prefs.Preferences.userNodeForPackage(com.schroedingersblock.core.SchroedingersBlockPlugin.class);
        String defaultMode = prefs.get(com.schroedingersblock.core.SchroedingersBlockPlugin.DEFAULT_MODE_PREF, "Basic");
        levelBox.setSelectedItem(defaultMode);
        levelBox.setToolTipText("Choose how detailed you want the AI to explain the system to you.");
        //Tooltips für die Einträge im Dropdown (Hover)
        levelBox.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                JComponent comp = (JComponent) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);

                // index -1 ist der aktuell ausgewählte Eintrag in der Box
                // index >= 0 sind die Einträge in der aufgeklappten Liste
                if (value != null) {
                    if ("Basic".equals(value)) {
                        comp.setToolTipText("Short, concise explanations.");
                    } else if ("Expert".equals(value)) {
                        comp.setToolTipText("Long, detailed explanations.");
                    }
                }
                return comp;
            }
        });

        runBtn = new JButton("RUN");
        runBtn.setEnabled(false); // Deaktiviert bis Export abgeschlossen

        controlPanel.add(new JLabel("Detail Level: "));
        controlPanel.add(levelBox);
        controlPanel.add(runBtn);

        headerPanel.add(controlPanel, BorderLayout.EAST);
        add(headerPanel, BorderLayout.NORTH);

        // --- Chat Bereich (Mitte) ---
        chatPanel = new ScrollablePanel();
        chatPanel.setLayout(new BoxLayout(chatPanel, BoxLayout.Y_AXIS));
        scrollPane = new JScrollPane(chatPanel);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);

        add(scrollPane, BorderLayout.CENTER);

        // --- Eingabebereich (Süden) ---
        JPanel bottomPanel = new JPanel(new BorderLayout(5, 5));
        bottomPanel.setBorder(BorderFactory.createEmptyBorder(5, 10, 10, 10));

        inputField = new JTextField();
        JButton sendBtn = new JButton("Send");

        bottomPanel.add(new JLabel("Follow-up: "), BorderLayout.WEST);
        bottomPanel.add(inputField, BorderLayout.CENTER);
        bottomPanel.add(sendBtn, BorderLayout.EAST);

        sendBtn.addActionListener(e -> sendMessage());
        inputField.addActionListener(e -> sendMessage());

        // HIER GEÄNDERT: Nur das Eingabefeld im Süden, keine doppelte Statusleiste mehr!
        add(bottomPanel, BorderLayout.SOUTH);

        // Logik für den RUN Button ---
        runBtn.setToolTipText("Start AI analysis.");
        runBtn.addActionListener(e -> {
            apiService.setDetailLevel((String) levelBox.getSelectedItem());

            // Diese Nachricht dient nun als temporärer Ladeindikator.
            addMessage("AI", "Preparing and uploading model context to AI\u00A0...");

            fetchData(mode, true);
        });
    }

    private void sendMessage() {
        String text = inputField.getText().trim();
        if (!text.isEmpty()) {
            addMessage("User", text);
            inputField.setText("");
            addMessage("AI", "Processing\u00A0...");
            fetchData(text, false);
        }
    }

    private void fetchData(String query, boolean isInitial) {
        SwingWorker<String, Void> worker = new SwingWorker<>() {
            @Override
            protected String doInBackground() {
                try {
                    Thread.sleep(2000); // Simulation der Latenz
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                if (isInitial) {
                    return apiService.fetchExplanation(targetElement, mode);
                } else {
                    return apiService.fetchFollowUp(query);
                }
            }

            @Override
            protected void done() {
                try {
                    removeLastMessage(); // Entfernt "Processing..."
                    addMessage("AI", get());
                } catch (Exception e) {
                    addMessage("System", "Error: Connection failed.");
                }
            }
        };
        worker.execute();
    }

    private void addMessage(String sender, String message) {
        JPanel row = new JPanel(new BorderLayout());
        JEditorPane textPane = createTextPane(sender, message);

        row.add(textPane, BorderLayout.CENTER);
        chatPanel.add(row);
        chatPanel.revalidate();

        SwingUtilities.invokeLater(() -> {
            if ("AI".equals(sender)) {
                // KI-Antworten: Scrolle zum Anfang der Nachricht
                chatPanel.scrollRectToVisible(new Rectangle(0, row.getY(), 1, 1));
            } else {
                // User/System: Scrolle zum Ende
                JScrollBar bar = scrollPane.getVerticalScrollBar();
                bar.setValue(bar.getMaximum());
            }
        });
    }

    private void removeLastMessage() {
        int count = chatPanel.getComponentCount();
        if (count > 0) {
            chatPanel.remove(count - 1);
            chatPanel.revalidate();
            chatPanel.repaint();
        }
    }

    private void runExportTask() {
        SwingWorker<Boolean, Void> exportWorker = new SwingWorker<>() {
            @Override
            protected Boolean doInBackground() {
                Project project = Application.getInstance().getProject();
                if (project == null) return false;
                Element scope = "Model View".equals(mode) ? null : targetElement;
                return DataExtractionService.executeJsonExport(project, scope);
            }

            @Override
            protected void done() {
                try {
                    if (get()) {
                        addMessage("System", "Model data successfully exported.");
                        addMessage("System", "Select detail level and press <b>RUN</b>.");
                        runBtn.setEnabled(true);
                    } else {
                        addMessage("System", "Error: Model export failed.");
                    }
                } catch (Exception e) {
                    addMessage("System", "Critical Error: " + e.getMessage());
                }
            }
        };
        exportWorker.execute();
    }

    // Hilfsmethode für den Namen des Elements
    private String getTargetDisplayName() {
        String type = (targetElement != null) ? targetElement.getHumanType() : "Unknown";
        String name = "";
        if (targetElement instanceof NamedElement) {
            String tempName = ((NamedElement) targetElement).getName();
            if (tempName != null && !tempName.isEmpty()) {
                name = " '" + tempName + "'";
            }
        }
        return type + name;
    }

    private JPanel createTitleArea() {
        JPanel titleArea = new JPanel();
        titleArea.setLayout(new BoxLayout(titleArea, BoxLayout.Y_AXIS));
        titleArea.setOpaque(false);

        JLabel titleLabel = new JLabel("SysML Analysis: " + mode);
        titleLabel.setFont(new Font("Arial", Font.BOLD, 16));
        titleArea.add(titleLabel);

        JLabel subtitleLabel = new JLabel("Target: " + getTargetDisplayName());
        subtitleLabel.setFont(new Font("Arial", Font.ITALIC, 12));
        subtitleLabel.setForeground(Color.GRAY);
        titleArea.add(subtitleLabel);

        return titleArea;
    }

    private JEditorPane createTextPane(String sender, String message) {
        JEditorPane textPane = new JEditorPane();
        textPane.setContentType("text/html");
        String htmlContent = "<html><body style='font-family: sans-serif; font-size: 11px; margin: 0; padding: 0;'>"
                + "<b>" + sender + ":</b><br>"
                + message.replace("\n", "<br>")
                + "</body></html>";
        textPane.setText(htmlContent);
        textPane.setEditable(false);
        textPane.setOpaque(false);
        textPane.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        return textPane;
    }

    private static class ScrollablePanel extends JPanel implements Scrollable {
        @Override
        public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) { return 16; }
        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) { return 16; }
        @Override
        public boolean getScrollableTracksViewportWidth() { return true; }
        @Override
        public boolean getScrollableTracksViewportHeight() { return false; }
    }
}