/**
 * Die grafische Benutzeroberfläche/Chat-Dialog für die Interaktion mit dem Plugin.
 */

package com.schroedingersblock.ui;

import com.schroedingersblock.api.DummyExplanationService;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.Element;
import com.nomagic.uml2.ext.magicdraw.classes.mdkernel.NamedElement; // NEU: Wichtig für getName()
import javax.swing.*;
import java.awt.*;

// Pop-up-Fenster (JDialog)
public class ExplanationDialog extends JDialog {
    private JPanel chatPanel;
    private JScrollPane scrollPane;
    private JTextField inputField;
    private final String mode;
    private final Element targetElement;

    // Hier wird die LLM Logik angebunden
    private final DummyExplanationService apiService = new DummyExplanationService();

    public ExplanationDialog(String mode, Element targetElement) {
        this.mode = mode;
        this.targetElement = targetElement;

        setTitle("Schrödinger's Block - SYSML AI Analysis");
        setSize(550, 600);
        setLocationRelativeTo(null);
        setAlwaysOnTop(true);
        setLayout(new BorderLayout(10, 10));

        buildUI();

        // Start-Anfrage beim Öffnen des Fensters (mit geschütztem Leerzeichen)
        addMessage("System", "Generating initial SysML explanation\u00A0...");
        fetchData(mode, true);
    }

    private void buildUI() {
        JLabel header = new JLabel(" SysML Analysis: " + mode);
        header.setFont(new Font("Arial", Font.BOLD, 16));
        add(header, BorderLayout.NORTH);

        chatPanel = new ScrollablePanel(); //Erzwingt den Zeilenumbruch
        chatPanel.setLayout(new BoxLayout(chatPanel, BoxLayout.Y_AXIS));
        scrollPane = new JScrollPane(chatPanel);

        //Scroll-Geschwindigkeit und horizontales Scrollen anpassen
        scrollPane.getVerticalScrollBar().setUnitIncrement(16); // 16px pro Tick (flüssiges, schnelles Scrollen)
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER); // Verhindert endloses Wachstum nach rechts

        add(scrollPane, BorderLayout.CENTER);

        // --- Eingabebereich ---
        JPanel bottomPanel = new JPanel(new BorderLayout(5, 5));
        inputField = new JTextField();
        JButton sendBtn = new JButton("Send");

        bottomPanel.add(new JLabel("Follow-up: "), BorderLayout.WEST);
        bottomPanel.add(inputField, BorderLayout.CENTER);
        bottomPanel.add(sendBtn, BorderLayout.EAST);

        // Wenn "Send" geklickt oder Enter gedrückt wird:
        sendBtn.addActionListener(e -> sendMessage());
        inputField.addActionListener(e -> sendMessage());

        // --- Container für Eingabe + Statusleiste (Süden) ---
        JPanel southContainer = new JPanel(new BorderLayout());
        southContainer.add(bottomPanel, BorderLayout.CENTER);

        // Statusleiste erstellen (Englisch + Typ & Spezifischer Name)
        String type = (targetElement != null) ? targetElement.getHumanType() : "Unknown";
        String name = "";
        if (targetElement instanceof NamedElement) {
            String tempName = ((NamedElement) targetElement).getName();
            if (tempName != null && !tempName.isEmpty()) {
                name = " '" + tempName + "'";
            }
        }

        String displayName = type + name;

        JLabel statusBar = new JLabel(" Analyzed Target: " + displayName);
        statusBar.setFont(new Font("Arial", Font.ITALIC, 11));
        statusBar.setForeground(Color.GRAY);
        statusBar.setBorder(BorderFactory.createEmptyBorder(2, 5, 2, 5));

        southContainer.add(statusBar, BorderLayout.SOUTH);
        add(southContainer, BorderLayout.SOUTH);
    }

    // "Send" geklickt:
    private void sendMessage() {
        String text = inputField.getText().trim();
        if (!text.isEmpty()) {
            addMessage("User", text);
            inputField.setText("");
            addMessage("AI", "Processing\u00A0..."); // Typo & Leerzeichen korrigiert
            fetchData(text, false);
        }
    }

    // Datenabfragen Multithreading (mit Hintergrund-Pfaden) - Typo korrigiert
    private void fetchData(String query, boolean isInitial) {
        SwingWorker<String, Void> worker = new SwingWorker<>() {
            @Override
            protected String doInBackground() {
                try {
                    Thread.sleep(4000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                // Datenübergabe an die API LLM
                if (isInitial) {
                    return apiService.fetchExplanation(targetElement, mode);
                } else {
                    return apiService.fetchFollowUp(query);
                }
            }

            @Override
            protected void done() {
                try {
                    removeLastMessage();
                    addMessage("AI", get());
                } catch (Exception e) {
                    addMessage("System", "Error: Connection failed.");
                }
            }
        };
        worker.execute();
    }

    // Nachricht im Fenster hinzufügen:
    private void addMessage(String sender, String message) {
        JPanel row = new JPanel(new BorderLayout());

        // --- REFACTORING: JEditorPane statt JLabel für flexiblen Textumbruch ---
        JEditorPane textPane = new JEditorPane();
        textPane.setContentType("text/html");

        // Inline-CSS für ein sauberes Erscheinungsbild ohne harte Breiten (px)
        String htmlContent = "<html><body style='font-family: sans-serif; font-size: 11px; margin: 0; padding: 0;'>"
                + "<b>" + sender + ":</b><br>"
                + message.replace("\n", "<br>")
                + "</body></html>";

        textPane.setText(htmlContent);
        textPane.setEditable(false);
        textPane.setOpaque(false); // Transparenter Hintergrund, damit es wie ein Label aussieht
        textPane.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        row.add(textPane, BorderLayout.CENTER);
        chatPanel.add(row);
        chatPanel.revalidate(); // Fenstergröße anpassen/aktualisieren

        // Scrollt die Ansicht im Fenster automatisch nach unten
        SwingUtilities.invokeLater(() -> {
            JScrollBar bar = scrollPane.getVerticalScrollBar();
            bar.setValue(bar.getMaximum());
        });
    }

    private void removeLastMessage() {
        int count = chatPanel.getComponentCount();
        if (count > 0) {
            chatPanel.remove(count - 1); // Typo "Prozessing" im Kommentar korrigiert
            chatPanel.revalidate();
            chatPanel.repaint();
        }
    }

    // Hilfsklasse für dynamischen Zeilenumbruch
    // Implementiert das Scrollable-Interface, um dem JScrollPane zu sagen:
    // "Bitte pass meine Breite immer exakt an die Fensterbreite an!"
    private class ScrollablePanel extends JPanel implements Scrollable {
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