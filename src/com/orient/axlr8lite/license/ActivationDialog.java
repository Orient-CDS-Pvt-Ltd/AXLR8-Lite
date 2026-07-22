// Copyright 2026 OrientCDS Private Limited. Licensed under Apache 2.0.
package com.orient.axlr8lite.license;

import com.orient.axlr8lite.views.RegistrationDialog;

import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.IMessageProvider;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.PlatformUI;

/**
 * License-key entry — paste one key, nothing else.
 *
 * <p>Second half of the first-run flow: the user has submitted the Google Form,
 * an automated mail has delivered their lifetime key, and they paste it here.
 * The key carries the licensee's address, so there is no second field to fill
 * in and nothing to mistype. Verification is offline and instant.
 *
 * <p>The dialog is closable. Enforcement lives in the chat view, which stays
 * locked until {@link LicenseManager#isActivated()} is true — trapping the user
 * in an unclosable modal would hold their IDE hostage without adding any real
 * enforcement.
 */
public final class ActivationDialog extends TitleAreaDialog {

    private static final int OPEN_FORM_ID = IDialogConstants.CLIENT_ID + 1;

    private Text keyField;

    private ActivationDialog(Shell parent) {
        super(parent);
        setHelpAvailable(false);
        setShellStyle(getShellStyle() | SWT.RESIZE);
    }

    /**
     * Show the dialog and return whether the plugin ended up activated.
     * Safe to call when already activated — returns true immediately.
     */
    public static boolean prompt(Shell parent) {
        if (LicenseManager.isActivated()) return true;
        Shell shell = parent != null ? parent
            : PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell();
        new ActivationDialog(shell).open();
        return LicenseManager.isActivated();
    }

    /** Convenience overload for menu / toolbar actions. */
    public static boolean prompt() {
        return prompt(null);
    }

    @Override
    public void create() {
        super.create();
        getShell().setText("Activate AXLR8 Lite");
        setTitle("Paste your license key");
        setMessage("Submit the registration form and your free lifetime key is emailed "
            + "to you automatically. Paste it below — that is all we need.",
            IMessageProvider.INFORMATION);
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite area = (Composite) super.createDialogArea(parent);

        Composite c = new Composite(area, SWT.NONE);
        c.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        GridLayout gl = new GridLayout(1, false);
        gl.marginWidth = 24;
        gl.marginHeight = 16;
        gl.verticalSpacing = 8;
        c.setLayout(gl);

        Label keyLbl = new Label(c, SWT.NONE);
        keyLbl.setText("License key:");

        keyField = new Text(c, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);
        GridData kGd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        kGd.widthHint = 460;
        kGd.heightHint = 72;
        keyField.setLayoutData(kGd);
        keyField.setMessage("AXL8-…");

        // The key is long, so offer a one-click paste.
        Button paste = new Button(c, SWT.PUSH);
        paste.setText("Paste from clipboard");
        paste.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        paste.addListener(SWT.Selection, e -> pasteKey());

        Label note = new Label(c, SWT.WRAP);
        note.setText("Your key never expires and is checked entirely offline — no internet "
            + "connection is needed after activation.\n\n"
            + "Key not arrived? Check your spam folder, or write to "
            + LicenseManager.SUPPORT_EMAIL + ".");
        GridData nGd = new GridData(SWT.FILL, SWT.TOP, true, false);
        nGd.verticalIndent = 8;
        nGd.widthHint = 460;
        note.setLayoutData(nGd);

        return area;
    }

    private void pasteKey() {
        Clipboard cb = new Clipboard(getShell().getDisplay());
        try {
            Object contents = cb.getContents(TextTransfer.getInstance());
            if (contents instanceof String text && !text.isBlank()) {
                keyField.setText(text.trim());
                setErrorMessage(null);
            } else {
                setErrorMessage("There is no text on the clipboard to paste.");
            }
        } finally {
            cb.dispose();
        }
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, OPEN_FORM_ID, "Open registration form", false);
        createButton(parent, IDialogConstants.OK_ID, "Activate", true);
        createButton(parent, IDialogConstants.CANCEL_ID, "Later", false);
    }

    @Override
    protected void buttonPressed(int buttonId) {
        if (buttonId == OPEN_FORM_ID) {
            RegistrationDialog.showOnDemand();
            return;
        }
        super.buttonPressed(buttonId);
    }

    @Override
    protected void okPressed() {
        LicenseKey.Result result = LicenseManager.activate(keyField.getText().trim());
        if (!result.isValid()) {
            setErrorMessage(result.getReason());
            return;
        }
        setErrorMessage(null);
        super.okPressed();
    }
}
