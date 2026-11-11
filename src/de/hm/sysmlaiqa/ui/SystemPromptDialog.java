package de.hm.sysmlaiqa.ui;

import de.hm.sysmlaiqa.api.ModelExport;
import de.hm.sysmlaiqa.api.PromptLoader;

import javax.swing.*;
import java.awt.*;
import java.io.File;

/**
 * "System prompt…": System-Prompt im Fenster ansehen und ändern (FR9).
 * Gespeichert wird eine eigene Fassung unter Dokumente/SysML-AI-QA/prompts (ohne Administratorrechte).
 * Jede verwendete Fassung wird beim Senden mit Prüfsumme archiviert und im Log vermerkt (system_prompt_sha/_source).
 */
public class SystemPromptDialog extends JDialog {

    private final File baseDir;
    private final Runnable onChange;
    private final JComboBox<String> formatBox = new JComboBox<>(new String[]{ExplanationDialog.FORMAT_V1_LABEL, ExplanationDialog.FORMAT_V2_LABEL});
    private final JTextArea area = new JTextArea();
    private final JLabel sourceLabel = new JLabel();
    private String shownFormat;
    private String loadedText = "";

    public SystemPromptDialog(Window owner, File baseDir, String format, Runnable onChange) {
        super(owner, "System prompt", ModalityType.APPLICATION_MODAL);
        this.baseDir = baseDir;
        this.onChange = onChange;
        setSize(760, 520);
        setLocationRelativeTo(owner);
        setLayout(new BorderLayout(6, 6));

        JPanel top = new JPanel(new BorderLayout(6, 4));
        top.setBorder(BorderFactory.createEmptyBorder(8, 10, 0, 10));
        JPanel fmt = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        fmt.add(new JLabel("Prompt for:"));
        fmt.add(formatBox);
        top.add(fmt, BorderLayout.NORTH);
        JLabel hint = new JLabel("<html>This text is sent to the AI as system instruction with every request in this format.<br>"
                + "<b>{idInstruction}</b> is replaced by the instruction to cite element ids when \"Cite element IDs\" is on, otherwise removed.<br>"
                + "The system prompt is an experiment variable: every version is archived with its checksum and named in the log.</html>");
        top.add(hint, BorderLayout.CENTER);
        top.add(sourceLabel, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);

        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane sp = new JScrollPane(area);
        sp.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createEmptyBorder(0, 10, 0, 10), sp.getBorder()));
        add(sp, BorderLayout.CENTER);

        JButton save = new JButton("Save");
        save.addActionListener(e -> {
            if (save()) dispose();
        });
        JButton reset = new JButton("Reset to default");
        reset.setToolTipText("Deletes your own version; the default (or installed) prompt is used again.");
        reset.addActionListener(e -> {
            if (JOptionPane.showConfirmDialog(this, "Delete your own version of this prompt and use the default again?",
                    getTitle(), JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
            if (!PromptLoader.resetCustom(shownFormat)) {
                JOptionPane.showMessageDialog(this, "Could not delete " + PromptLoader.customFile(shownFormat), getTitle(), JOptionPane.ERROR_MESSAGE);
            }
            load(shownFormat);
            onChange.run();
        });
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(reset);
        buttons.add(save);
        buttons.add(cancel);
        add(buttons, BorderLayout.SOUTH);

        formatBox.setSelectedItem(ModelExport.FORMAT_V2.equals(format) ? ExplanationDialog.FORMAT_V2_LABEL : ExplanationDialog.FORMAT_V1_LABEL);
        load(format);
        formatBox.addActionListener(e -> {
            String f = ExplanationDialog.FORMAT_V2_LABEL.equals(formatBox.getSelectedItem()) ? ModelExport.FORMAT_V2 : ModelExport.FORMAT_V1;
            if (f.equals(shownFormat)) return;
            if (!area.getText().equals(loadedText)) {
                int c = JOptionPane.showConfirmDialog(this, "Save your changes to the current prompt first?", getTitle(), JOptionPane.YES_NO_CANCEL_OPTION);
                if (c == JOptionPane.CANCEL_OPTION || (c == JOptionPane.YES_OPTION && !save())) {
                    formatBox.setSelectedItem(ModelExport.FORMAT_V2.equals(shownFormat) ? ExplanationDialog.FORMAT_V2_LABEL : ExplanationDialog.FORMAT_V1_LABEL);
                    return;
                }
            }
            load(f);
        });
    }

    private void load(String format) {
        shownFormat = format;
        PromptLoader.Template t = PromptLoader.loadTemplate(baseDir, format);
        loadedText = t.text.replace("\r\n", "\n");
        area.setText(loadedText);
        area.setCaretPosition(0);
        String src;
        switch (t.source) {
            case PromptLoader.SOURCE_CUSTOM: src = "your own version (" + t.file.getAbsolutePath() + ")"; break;
            case PromptLoader.SOURCE_INSTALLED: src = "installed file (" + t.file.getAbsolutePath() + ")"; break;
            default: src = "default"; break;
        }
        sourceLabel.setText("Currently used: " + src);
    }

    private boolean save() {
        String text = area.getText();
        if (text.equals(loadedText)) return true;
        if (text.isBlank()) {
            JOptionPane.showMessageDialog(this, "The system prompt must not be empty. Use \"Reset to default\" instead.",
                    getTitle(), JOptionPane.WARNING_MESSAGE);
            return false;
        }
        try {
            PromptLoader.saveCustom(shownFormat, text);
            loadedText = text;
            onChange.run();
            return true;
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Could not save: " + ex.getMessage(), getTitle(), JOptionPane.ERROR_MESSAGE);
            return false;
        }
    }
}
