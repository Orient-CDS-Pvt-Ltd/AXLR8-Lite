// Copyright 2026 OrientCDS Private Limited. Licensed under Apache 2.0.
package com.orient.axlr8lite.views;

import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.IMessageProvider;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.PlatformUI;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

/**
 * One-time registration form.
 *
 * <p>Collects basic details (name, company, designation, email, country,
 * source) and opens the OrientCDS Google Form with those values pre-filled,
 * for product analytics. The user reviews and clicks Submit on the form page —
 * no data is sent silently from the plugin.
 *
 * <p>Shown once on first chat-view open. Whether the user submits or skips,
 * a preference flag is set so it never nags again.
 */
public final class RegistrationDialog extends TitleAreaDialog {

    private static final Logger LOG = Logger.getLogger(RegistrationDialog.class.getName());

    // ── Google Form (pre-fill) endpoint + field entry IDs ──────────
    private static final String FORM_VIEW =
        "https://docs.google.com/forms/d/e/"
        + "1FAIpQLSeSnUeIPgoZFGdZ_5ErlXLmI8pjUAjSsnHbdqfRW8OZf5AbZA/viewform";

    private static final String ENTRY_NAME        = "entry.1820172337";
    private static final String ENTRY_COMPANY     = "entry.2118217642";
    private static final String ENTRY_DESIGNATION = "entry.786983292";
    private static final String ENTRY_EMAIL       = "entry.1357752596";
    private static final String ENTRY_COUNTRY     = "entry.342909607";
    private static final String ENTRY_SOURCE      = "entry.299706576";

    private Text nameField, companyField, designationField,
                 emailField, countryField, sourceField;

    public RegistrationDialog(Shell parent) {
        super(parent);
        setHelpAvailable(false);
        setShellStyle(getShellStyle() | SWT.RESIZE);
    }

    @Override
    public void create() {
        super.create();
        getShell().setText("Welcome to AXLR8 Lite");
        setTitle("Register — AXLR8 Lite");
        setMessage("Tell us a little about yourself. It helps us improve the plugin. "
            + "You'll review the form before submitting.", IMessageProvider.INFORMATION);
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite area = (Composite) super.createDialogArea(parent);
        Composite c = new Composite(area, SWT.NONE);
        c.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        GridLayout gl = new GridLayout(2, false);
        gl.marginWidth = 24;
        gl.marginHeight = 16;
        gl.verticalSpacing = 10;
        gl.horizontalSpacing = 12;
        c.setLayout(gl);

        nameField        = field(c, "Full Name *", "");
        companyField     = field(c, "Company / Organisation *", "");
        designationField = field(c, "Designation *", "e.g. SAP Developer, Architect…");
        emailField       = field(c, "Work Email *", "");
        countryField     = field(c, "Country *", "");
        sourceField      = field(c, "How did you hear about us?", "e.g. Eclipse Marketplace, LinkedIn…");

        Label note = new Label(c, SWT.WRAP);
        note.setText("Fields marked * are required. We use your details only to understand "
            + "who's using the plugin and to share important updates.");
        GridData noteGd = new GridData(SWT.FILL, SWT.TOP, true, false);
        noteGd.horizontalSpan = 2;
        noteGd.verticalIndent = 4;
        noteGd.widthHint = 420;
        note.setLayoutData(noteGd);

        return area;
    }

    private Text field(Composite parent, String labelText, String hint) {
        Label lbl = new Label(parent, SWT.NONE);
        lbl.setText(labelText + ":");
        lbl.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, false, false));
        Text t = new Text(parent, SWT.BORDER);
        GridData gd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        gd.widthHint = 260;
        t.setLayoutData(gd);
        if (hint != null && !hint.isEmpty()) t.setMessage(hint);
        return t;
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, IDialogConstants.OK_ID, "Review & Submit →", true);
        // No Skip — registration is mandatory before the plugin can be used.
    }

    @Override
    protected void handleShellCloseEvent() {
        // Block the X button — user must submit the form to proceed.
    }

    @Override
    protected void okPressed() {
        String name        = nameField.getText().trim();
        String company     = companyField.getText().trim();
        String designation = designationField.getText().trim();
        String email       = emailField.getText().trim();
        String country     = countryField.getText().trim();
        String source      = sourceField.getText().trim();

        if (name.isEmpty())        { setErrorMessage("Please enter your full name.");    return; }
        if (company.isEmpty())     { setErrorMessage("Please enter your company.");      return; }
        if (designation.isEmpty()) { setErrorMessage("Please enter your designation."); return; }
        if (email.isEmpty() || !email.contains("@")) {
            setErrorMessage("Please enter a valid work email.");
            return;
        }
        if (country.isEmpty())     { setErrorMessage("Please enter your country.");      return; }
        setErrorMessage(null);

        openPrefilledForm(name, company, designation, email, country, source);
        super.okPressed();
    }

    private void openPrefilledForm(String name, String company, String designation,
                                   String email, String country, String source) {
        String url = FORM_VIEW + "?usp=pp_url"
            + "&" + enc(ENTRY_NAME, name)
            + "&" + enc(ENTRY_COMPANY, company)
            + "&" + enc(ENTRY_DESIGNATION, designation)
            + "&" + enc(ENTRY_EMAIL, email)
            + "&" + enc(ENTRY_COUNTRY, country)
            + "&" + enc(ENTRY_SOURCE, source);
        openUrl(url);
    }

    private static String enc(String entryId, String value) {
        return entryId + "=" + URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static void openUrl(String url) {
        try {
            PlatformUI.getWorkbench().getBrowserSupport()
                .getExternalBrowser()
                .openURL(new java.net.URI(url).toURL());
        } catch (Exception e) {
            LOG.warning("Failed to open registration form: " + e.getMessage());
        }
    }

    // ─── First-run gate ─────────────────────────────────────────────

    static final String KEY_REGISTERED = "lite.registered";

    public static boolean isRegistered() {
        return com.abapai.plugin.activator.Activator.getDefault()
            .getPreferenceStore().getBoolean(KEY_REGISTERED);
    }

    /**
     * Show the registration dialog once, on first chat-view open. Returns
     * immediately if the user has already seen it (submitted or skipped).
     */
    public static void showOnceOnFirstRun() {
        org.eclipse.jface.preference.IPreferenceStore store =
            com.abapai.plugin.activator.Activator.getDefault().getPreferenceStore();
        if (store.getBoolean(KEY_REGISTERED)) return;

        Shell shell = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell();
        new RegistrationDialog(shell).open();
        // Mark as shown regardless of submit/skip so it never nags again.
        store.setValue(KEY_REGISTERED, true);
    }

    /** Open the registration form on demand (e.g. from a "Register" action). */
    public static void showOnDemand() {
        Shell shell = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell();
        new RegistrationDialog(shell).open();
    }
}
