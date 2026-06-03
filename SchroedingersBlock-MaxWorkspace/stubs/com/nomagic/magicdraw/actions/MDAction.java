package com.nomagic.magicdraw.actions;

import com.nomagic.actions.NMAction;

public abstract class MDAction extends NMAction {
    protected MDAction(String id, String name, Integer key, String group) {
        super(id, name, key, group);
    }
}
