// Copyright 2026 OrientCDS Private Limited. Licensed under Apache 2.0.
package com.orient.axlr8lite.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.handlers.HandlerUtil;

/**
 * Command handler for {@code com.orient.axlr8lite.cmd.openChat}.
 *
 * <p>Opens the AXLR8 Lite Chat view (or activates it if already open).
 * Bound to {@code Ctrl+Shift+A} via plugin.xml.
 */
public class OpenLiteChatHandler extends AbstractHandler {

    public static final String VIEW_ID = "com.orient.axlr8lite.view.chat";

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        IWorkbenchPage page = HandlerUtil.getActiveWorkbenchWindowChecked(event).getActivePage();
        if (page == null) return null;
        try {
            page.showView(VIEW_ID);
        } catch (PartInitException e) {
            throw new ExecutionException("Could not open AXLR8 Lite Chat view", e);
        }
        return null;
    }
}
