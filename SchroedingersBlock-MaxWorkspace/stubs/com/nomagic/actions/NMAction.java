package com.nomagic.actions;

import javax.swing.AbstractAction;
import java.awt.event.ActionEvent;

public abstract class NMAction extends AbstractAction {
    protected NMAction(String id, String name, Integer key, String group) {
        super(name);
    }
    @Override
    public void actionPerformed(ActionEvent e) {}
}
