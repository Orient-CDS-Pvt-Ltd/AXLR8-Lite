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
import org.eclipse.ui.PlatformUI;

import java.util.logging.Logger;

/**
 * "Premium feature" upsell popup.
 *
 * <p>Shown when a Lite user clicks a Full-only menu item, or on first install
 * as a welcome.
 */
public class UpsellDialog extends TitleAreaDialog {

    private static final Logger LOG = Logger.getLogger(UpsellDialog.class.getName());

    public static final String MARKETPLACE_URL =
        "https://marketplace.eclipse.org/content/"
        + "ai-coder-eclipse-adt-abap-claude-code-openai-gemini-codex-github-copilot-models";

    public static final String WEBSITE_URL = "https://aiplugin.genesispro.ai/";

    private final String featureName;
    private final String featureDescription;

    private static final int ID_MARKETPLACE = 9001;
    private static final int ID_WEBSITE     = 9002;

    public UpsellDialog(Shell parent, String featureName, String featureDescription) {
        super(parent);
        this.featureName = featureName;
        this.featureDescription = featureDescription;
        setHelpAvailable(false);
    }

    @Override
    protected void configureShell(Shell newShell) {
        super.configureShell(newShell);
        newShell.setText("Full Version Feature");
        newShell.setSize(580, 420);
    }

    @Override
    public void create() {
        super.create();
        setTitle("✦ " + featureName);
        setMessage(
            "This feature is part of the full AXLR8 + ACTV8 plugin — "
            + "try it free for 90 days.",
            IMessageProvider.INFORMATION);
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite area = (Composite) super.createDialogArea(parent);

        Composite root = new Composite(area, SWT.NONE);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth  = 20;
        layout.marginHeight = 20;
        layout.verticalSpacing = 12;
        root.setLayout(layout);
        root.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        Label desc = new Label(root, SWT.WRAP);
        desc.setText(featureDescription);
        GridData descGd = new GridData(SWT.FILL, SWT.TOP, true, false);
        descGd.widthHint = 500;
        desc.setLayoutData(descGd);

        Label sep = new Label(root, SWT.SEPARATOR | SWT.HORIZONTAL);
        sep.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Label perks = new Label(root, SWT.WRAP);
        perks.setText("The full version adds:\n"
            + "  • 6 AI providers (vs 2 in Lite) — OpenAI, Claude API, Gemini, Codex CLI\n"
            + "  • 17 chat modes — Diagnose & Fix, Refactor, Convert (OO/RAP/SQL), and more\n"
            + "  • Live SAP schema fetched into every prompt\n"
            + "  • ACTV8 code generator — FSD -> ABAP project, deployed and AI-fixed\n"
            + "  • Safe SAP deploy (EHP8 + S/4) with auto AI Fix loops\n"
            + "  • TSD document generation\n"
            + "  • ATC + ST22 + Transport features\n\n"
            + "⭐ 90-day free trial. No credit card. After the trial, contact "
            + "neo@genesispro.ai for a commercial license.");
        GridData perksGd = new GridData(SWT.FILL, SWT.FILL, true, true);
        perksGd.widthHint = 500;
        perks.setLayoutData(perksGd);

        return area;
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, ID_MARKETPLACE,
            "Install from Eclipse Marketplace", true);
        createButton(parent, ID_WEBSITE,
            "Visit aiplugin.genesispro.ai", false);
        createButton(parent, IDialogConstants.CANCEL_ID,
            "Maybe Later", false);
    }

    @Override
    protected void buttonPressed(int buttonId) {
        if (buttonId == ID_MARKETPLACE) {
            openUrl(MARKETPLACE_URL);
            okPressed();
        } else if (buttonId == ID_WEBSITE) {
            openUrl(WEBSITE_URL);
            okPressed();
        } else {
            super.buttonPressed(buttonId);
        }
    }

    private static void openUrl(String url) {
        try {
            PlatformUI.getWorkbench().getBrowserSupport()
                .getExternalBrowser()
                .openURL(new java.net.URI(url).toURL());
        } catch (Exception e) {
            LOG.warning("Failed to open URL " + url + ": " + e.getMessage());
        }
    }

    // ─── Static convenience: open with parent shell from active workbench ───

    public static void showForFeature(String featureName, String description) {
        Shell shell = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell();
        new UpsellDialog(shell, featureName, description).open();
    }

    public static void showWelcome() {
        Shell shell = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell();
        new UpsellDialog(shell,
            "Welcome to AXLR8 Lite",
            "AXLR8 Lite is the free, open-source AI chat for ABAP. It gives you "
            + "2 LLM providers (Claude Code + GitHub Models), reads your active "
            + "editor, and respects your SAP target version (EHP8 or S/4HANA).\n\n"
            + "The full AXLR8 + ACTV8 plugin adds 4 more LLM providers, 16 more chat "
            + "modes, live SAP schema integration, the ACTV8 code generator, safe "
            + "SAP deploy, AI Fix loops, TSD generation, and more.\n\n"
            + "Take a look — 90 days free."
        ).open();
    }
}
