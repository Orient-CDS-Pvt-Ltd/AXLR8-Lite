// Copyright 2026 OrientCDS Private Limited. Licensed under Apache 2.0.
package com.orient.axlr8lite.preferences;

import com.abapai.plugin.activator.Activator;
import com.abapai.plugin.preferences.ABAPAIPreferences;
import com.abapai.plugin.preferences.ABAPAIPreferencePage;
import com.abapai.plugin.services.ai.AIBackend;
import com.abapai.plugin.services.ai.AIProvider;
import com.abapai.plugin.services.ai.AIServiceFactory;
import com.abapai.plugin.services.ai.AITaskProfile;
import com.abapai.plugin.services.ai.ClaudeCodeBackend;
import com.abapai.plugin.services.ai.ClaudeCodeDetector;
import com.abapai.plugin.services.ai.GitHubModelsBackend;
import com.abapai.plugin.services.backend.BackendMode;
import com.abapai.plugin.services.backend.BackendModeService;

import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * AXLR8 Lite preference page.
 *
 * <p>Mirrors the Claude Code + GitHub Models sections from the Full plugin's
 * preference page so both products share the same UX for the 2 backends Lite
 * supports. Detect button, model dropdowns, Test Connection — all present.
 *
 * <p>Full-only features (other LLM providers, RAG, compat layer, ACTV8,
 * deploy, etc.) appear as 🔒 locked groups with an Unlock button that opens
 * the {@link com.orient.axlr8lite.views.UpsellDialog}.
 */
public class LitePreferencePage extends PreferencePage
        implements IWorkbenchPreferencePage {

    private static final Logger LOG = Logger.getLogger(LitePreferencePage.class.getName());

    // ─── Active fields (Lite features) ──────────────────────────────
    private Combo  providerCombo;
    private Combo  modeCombo;
    private Button includeEditorCheck;

    // Claude Code
    private Label  claudeCodeStatusLabel;
    private Text   claudeCodePathField;
    private Combo  claudeCodeModelHeavyCombo;
    private Combo  claudeCodeModelLightCombo;

    // GitHub Models
    private Label  githubModelsStatusLabel;
    private Text   githubModelsPatField;
    private Combo  githubModelsModelHeavyCombo;
    private Combo  githubModelsModelLightCombo;

    public LitePreferencePage() {
        super("Orient AXLR8 Lite");
        setDescription("AI ABAP Chat — free, open-source. "
            + "Upgrade to AXLR8 + ACTV8 Full for code generation, deploy, AI Fix, and TSD.");
    }

    @Override
    public void init(IWorkbench workbench) {
        LitePreferences.seedDefaults();
    }

    @Override
    protected IPreferenceStore doGetPreferenceStore() {
        return Activator.getDefault().getPreferenceStore();
    }

    @Override
    protected Control createContents(Composite parent) {
        Composite root = new Composite(parent, SWT.NONE);
        root.setLayout(new GridLayout(1, false));
        root.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        createProviderGroup(root);
        createClaudeCodeGroup(root);
        createGitHubModelsGroup(root);
        createSapTargetGroup(root);
        createGeneralChatGroup(root);

        // Locked sections (Full-only)
        createLockedSection(root, "Other LLM Providers",
            "OpenAI · Claude API · Gemini · Codex CLI · Fallback chain");
        createLockedSection(root, "Workspace Context (RAG)",
            "Semantic search across your workspace · embedding-based context injection");
        createLockedSection(root, "Live SAP Context",
            "Fetch live class/table/BAPI definitions from SAP into every prompt");
        createLockedSection(root, "Compatibility Layer",
            "18-rule ABAP feature matrix · EHP8/7.40/7.50/7.54/S4 thresholds · auto-rewriter");
        createLockedSection(root, "Apply to SAP & Deploy",
            "Deploy artifacts · TR/Package creation · EHP8 + S/4 pipelines · AI Fix loop");
        createLockedSection(root, "ACTV8 Code Generator",
            "FSD → ABAP project · Cross-artifact validation · TSD doc generation");

        createUpgradeFooter(root);
        loadValues();
        return root;
    }

    // ─── Active groups ──────────────────────────────────────────────

    private void createProviderGroup(Composite parent) {
        Group g = group(parent, "AI Provider");
        new Label(g, SWT.NONE).setText("Provider:");
        providerCombo = new Combo(g, SWT.READ_ONLY);
        for (AIProvider p : AIProvider.values()) providerCombo.add(p.getLabel());
        providerCombo.setLayoutData(fillH());
    }

    private void createClaudeCodeGroup(Composite parent) {
        Group g = group(parent, "Claude Code Settings  (no API key — uses your local subscription)");

        // Status row + Detect button
        new Label(g, SWT.NONE).setText("Status:");
        Composite statusRow = new Composite(g, SWT.NONE);
        statusRow.setLayout(new GridLayout(2, false));
        statusRow.setLayoutData(fillH());
        claudeCodeStatusLabel = new Label(statusRow, SWT.WRAP);
        claudeCodeStatusLabel.setLayoutData(fillH());
        claudeCodeStatusLabel.setText("Click Detect to check...");
        Button detectBtn = new Button(statusRow, SWT.PUSH);
        detectBtn.setText("Detect");
        detectBtn.addListener(SWT.Selection, e -> {
            claudeCodeStatusLabel.setText("Detecting...");
            claudeCodeStatusLabel.getParent().layout(true, true);
            String overridePath = claudeCodePathField.getText().trim();
            CompletableFuture.supplyAsync(
                () -> ClaudeCodeDetector.detect(overridePath.isBlank() ? null : overridePath)
            ).thenAccept(result -> claudeCodeStatusLabel.getDisplay().asyncExec(() -> {
                if (!claudeCodeStatusLabel.isDisposed()) {
                    claudeCodeStatusLabel.setText(result.statusLine());
                    if (result.found() && claudeCodePathField.getText().isBlank() && result.path() != null)
                        claudeCodePathField.setText(result.path());
                    claudeCodeStatusLabel.getParent().layout(true, true);
                }
            }));
        });

        // CLI path override
        new Label(g, SWT.NONE).setText("CLI Path:");
        claudeCodePathField = new Text(g, SWT.BORDER);
        claudeCodePathField.setLayoutData(fillH());
        claudeCodePathField.setMessage("Leave blank for auto-detect (recommended)");

        // Heavy model
        new Label(g, SWT.NONE).setText("Model (complex tasks):");
        claudeCodeModelHeavyCombo = combo(g,
            new String[]{"claude-opus-4-8", "claude-opus-4-7", "claude-sonnet-4-6", "claude-opus-4-6"});

        // Light model
        new Label(g, SWT.NONE).setText("Model (chat / quick):");
        claudeCodeModelLightCombo = combo(g,
            new String[]{"claude-sonnet-4-6", "claude-haiku-4-5-20251001", "claude-opus-4-8", "claude-opus-4-7"});

        // Test Connection button (full width)
        Composite buttonRow = new Composite(g, SWT.NONE);
        buttonRow.setLayout(new GridLayout(1, false));
        GridData brGd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        brGd.horizontalSpan = 2;
        buttonRow.setLayoutData(brGd);
        Button testBtn = new Button(buttonRow, SWT.PUSH);
        testBtn.setText("Test Connection");
        testBtn.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, false, false));
        testBtn.addListener(SWT.Selection, e -> {
            testBtn.setEnabled(false);
            testBtn.setText("Testing...");
            CompletableFuture.supplyAsync(() -> {
                try {
                    ClaudeCodeBackend backend = new ClaudeCodeBackend();
                    AIBackend.Result result = backend.complete(
                        "You are a test assistant.",
                        java.util.List.of(new org.json.JSONObject()
                            .put("role", "user")
                            .put("content", "Reply with exactly: Claude Code OK")),
                        32, "low", AITaskProfile.CHAT_LITE, false);
                    return "Success: " + result.text();
                } catch (Exception ex) {
                    String detail = ex.getMessage();
                    if (detail == null || detail.isBlank())
                        detail = ex.getClass().getSimpleName();
                    return "Error: " + detail;
                }
            }).thenAccept(msg -> testBtn.getDisplay().asyncExec(() -> {
                if (!testBtn.isDisposed()) {
                    testBtn.setEnabled(true);
                    testBtn.setText("Test Connection");
                    MessageDialog.openInformation(testBtn.getShell(),
                        "Claude Code Test", msg);
                }
            }));
        });
    }

    private void createGitHubModelsGroup(Composite parent) {
        Group g = group(parent, "GitHub Models Settings");

        // Status row + Test Connection
        new Label(g, SWT.NONE).setText("Status:");
        Composite statusRow = new Composite(g, SWT.NONE);
        statusRow.setLayout(new GridLayout(2, false));
        statusRow.setLayoutData(fillH());
        githubModelsStatusLabel = new Label(statusRow, SWT.WRAP);
        githubModelsStatusLabel.setLayoutData(fillH());
        githubModelsStatusLabel.setText("Click Test Connection to verify token + model.");
        Button ghTestBtn = new Button(statusRow, SWT.PUSH);
        ghTestBtn.setText("Test Connection");
        ghTestBtn.addListener(SWT.Selection, e -> {
            ghTestBtn.setEnabled(false);
            ghTestBtn.setText("Testing...");
            final String testPat = githubModelsPatField.getText().trim();
            final String testModel = githubModelsModelHeavyCombo.getText();
            CompletableFuture.supplyAsync(() -> {
                try {
                    if (testPat.isBlank())
                        return "Error: PAT field is empty. Paste a GitHub token with models:read scope.";
                    GitHubModelsBackend backend = new GitHubModelsBackend(testPat, testModel);
                    AIBackend.Result result = backend.complete(
                        "You are a test assistant.",
                        java.util.List.of(new org.json.JSONObject()
                            .put("role", "user")
                            .put("content", "Reply with exactly: GitHub Models OK")),
                        32, "low", AITaskProfile.CHAT_LITE, false);
                    return "Success: " + result.text();
                } catch (Exception ex) {
                    String detail = ex.getMessage();
                    if (detail == null || detail.isBlank())
                        detail = ex.getClass().getSimpleName();
                    return "Error: " + detail;
                }
            }).thenAccept(msg -> ghTestBtn.getDisplay().asyncExec(() -> {
                if (!ghTestBtn.isDisposed()) {
                    ghTestBtn.setEnabled(true);
                    ghTestBtn.setText("Test Connection");
                    githubModelsStatusLabel.setText(msg);
                    githubModelsStatusLabel.getParent().layout(true, true);
                }
            }));
        });

        // PAT field
        new Label(g, SWT.NONE).setText("PAT (models:read):");
        githubModelsPatField = new Text(g, SWT.BORDER | SWT.PASSWORD);
        githubModelsPatField.setLayoutData(fillH());
        githubModelsPatField.setMessage("github_pat_... with models:read scope");

        // Heavy model (catalog-verified IDs from Full plugin)
        new Label(g, SWT.NONE).setText("Model (heavy):");
        githubModelsModelHeavyCombo = combo(g,
            new String[]{
                "openai/gpt-4.1",
                "openai/gpt-5",
                "openai/gpt-5-chat",
                "openai/gpt-4o",
                "openai/o3",
                "openai/o1",
                "meta/llama-4-maverick-17b-128e-instruct-fp8",
                "meta/llama-3.3-70b-instruct",
                "mistral-ai/codestral-2501",
                "mistral-ai/mistral-medium-2505",
                "deepseek/deepseek-r1",
                "deepseek/deepseek-v3-0324",
                "xai/grok-3",
                "microsoft/mai-ds-r1",
                "cohere/cohere-command-a"
            });

        // Light model
        new Label(g, SWT.NONE).setText("Model (light):");
        githubModelsModelLightCombo = combo(g,
            new String[]{
                "openai/gpt-4o-mini",
                "openai/gpt-4.1-mini",
                "openai/gpt-4.1-nano",
                "openai/gpt-5-mini",
                "openai/gpt-5-nano",
                "openai/o4-mini",
                "openai/o3-mini",
                "mistral-ai/mistral-small-2503",
                "mistral-ai/ministral-3b",
                "microsoft/phi-4-mini-instruct"
            });

        // Info label
        Label info = new Label(g, SWT.WRAP);
        info.setText(
            "Official GitHub Models inference endpoint. Use a GitHub PAT with models:read scope.\n"
            + "Free for GitHub Copilot subscribers. Get a token at:\n"
            + "    https://github.com/settings/personal-access-tokens\n\n"
            + "Model IDs use the inference format {publisher}/{name} — different from\n"
            + "the marketplace display labels (azure-openai/*, azureml-meta/* …) at\n"
            + "github.com/marketplace.");
        GridData infoGd = new GridData(SWT.FILL, SWT.TOP, true, false);
        infoGd.horizontalSpan = 2;
        infoGd.widthHint = 380;
        info.setLayoutData(infoGd);
    }

    private void createSapTargetGroup(Composite parent) {
        Group g = group(parent, "SAP Target");
        new Label(g, SWT.NONE).setText("Target system:");
        modeCombo = new Combo(g, SWT.READ_ONLY);
        modeCombo.add("S/4HANA (RAP, CDS, 7.54+ features)");
        modeCombo.add("EHP8 (classic, SAP_BASIS 7.50/7.52)");
        modeCombo.setLayoutData(fillH());

        Label info = new Label(g, SWT.WRAP);
        info.setText("Pick the system you're writing for — chat answers respect this.");
        GridData gd = new GridData(SWT.FILL, SWT.TOP, true, false, 2, 1);
        gd.widthHint = 380;
        info.setLayoutData(gd);
    }

    private void createGeneralChatGroup(Composite parent) {
        Group g = group(parent, "Chat behaviour");
        includeEditorCheck = new Button(g, SWT.CHECK);
        includeEditorCheck.setText("Include active editor file in chat prompts by default");
        GridData gd = new GridData(SWT.FILL, SWT.TOP, true, false, 2, 1);
        includeEditorCheck.setLayoutData(gd);
    }

    // ─── Locked sections ────────────────────────────────────────────

    private void createLockedSection(Composite parent, String featureKey, String summary) {
        Group g = group(parent, "🔒  " + featureKey + "  (Full Version)");
        Label desc = new Label(g, SWT.WRAP);
        desc.setText(summary);
        desc.setForeground(g.getDisplay().getSystemColor(SWT.COLOR_DARK_GRAY));
        GridData gd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        gd.widthHint = 380;
        desc.setLayoutData(gd);

        Button unlock = new Button(g, SWT.PUSH);
        unlock.setText("Unlock — Get Full");
        unlock.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, false, false));
        unlock.addListener(SWT.Selection, e ->
            com.orient.axlr8lite.views.UpsellDialog.showForFeature(
                featureKey,
                com.orient.axlr8lite.handlers.LockedFeatureHandler.descriptionFor(featureKey)));
    }

    // ─── Upgrade footer ─────────────────────────────────────────────

    private void createUpgradeFooter(Composite parent) {
        Group g = group(parent, "Upgrade");
        Label l = new Label(g, SWT.WRAP);
        l.setText("AXLR8 Lite is free and open source (Apache 2.0). "
            + "For live SAP context, code generation, deploy, AI Fix, and TSD, "
            + "try the full plugin with a 90-day free trial.\n\n"
            + "Visit: https://aiplugin.genesispro.ai/");
        GridData gd = new GridData(SWT.FILL, SWT.TOP, true, false, 2, 1);
        gd.widthHint = 480;
        l.setLayoutData(gd);
    }

    // ─── Load / save ────────────────────────────────────────────────

    private void loadValues() {
        // Provider
        AIProvider p = LitePreferences.getProvider();
        providerCombo.select(indexOf(p));

        // Claude Code
        String ccPath = LitePreferences.getClaudeCodePath();
        claudeCodePathField.setText("claude".equals(ccPath) ? "" : (ccPath == null ? "" : ccPath));
        selectCombo(claudeCodeModelHeavyCombo, ABAPAIPreferences.getClaudeCodeModelHeavy());
        selectCombo(claudeCodeModelLightCombo, ABAPAIPreferences.getClaudeCodeModelLight());

        // GitHub Models
        String pat = LitePreferences.getGitHubPat();
        githubModelsPatField.setText(pat == null ? "" : pat);
        selectCombo(githubModelsModelHeavyCombo, ABAPAIPreferences.getGitHubModelsModel());
        selectCombo(githubModelsModelLightCombo, ABAPAIPreferences.getGitHubModelsModelLight());

        // SAP target mode
        BackendMode mode = LitePreferences.getMode();
        modeCombo.select(mode == BackendMode.EHP8 ? 1 : 0);

        // Chat behaviour
        includeEditorCheck.setSelection(LitePreferences.isIncludeEditorByDefault());
    }

    @Override
    public boolean performOk() {
        IPreferenceStore store = getPreferenceStore();

        // Provider
        int pi = providerCombo.getSelectionIndex();
        if (pi >= 0 && pi < AIProvider.values().length) {
            LitePreferences.setProvider(AIProvider.values()[pi]);
        }

        // Claude Code
        store.setValue(ABAPAIPreferencePage.KEY_CLAUDE_CODE_PATH,
            claudeCodePathField.getText().trim());
        store.setValue(ABAPAIPreferencePage.KEY_CLAUDE_CODE_MODEL,
            claudeCodeModelHeavyCombo.getText());
        // Light model uses the same key family — write under a Lite-specific key if needed
        store.setValue("claude.code.model.light", claudeCodeModelLightCombo.getText());

        // GitHub Models
        store.setValue(ABAPAIPreferencePage.KEY_GITHUB_MODELS_PAT,
            githubModelsPatField.getText().trim());
        store.setValue(ABAPAIPreferencePage.KEY_GITHUB_MODELS_MODEL,
            githubModelsModelHeavyCombo.getText());
        store.setValue("github.models.model.light", githubModelsModelLightCombo.getText());

        // SAP target mode
        BackendMode mode = modeCombo.getSelectionIndex() == 1
            ? BackendMode.EHP8
            : BackendMode.S4HANA;
        LitePreferences.setMode(mode);

        // Chat behaviour
        LitePreferences.setIncludeEditorByDefault(includeEditorCheck.getSelection());

        // Force backend re-creation against the new settings
        AIServiceFactory.reset();
        return super.performOk();
    }

    @Override
    protected void performDefaults() {
        LitePreferences.seedDefaults();
        loadValues();
        super.performDefaults();
    }

    // ─── Layout helpers (mirror Full plugin patterns) ───────────────

    private static Group group(Composite parent, String title) {
        Group g = new Group(parent, SWT.NONE);
        g.setText(title);
        g.setLayout(new GridLayout(2, false));
        g.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        return g;
    }

    private static GridData fillH() {
        GridData gd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        gd.widthHint = 320;
        return gd;
    }

    private static Combo combo(Composite parent, String[] items) {
        Combo c = new Combo(parent, SWT.READ_ONLY);
        for (String item : items) c.add(item);
        if (items.length > 0) c.select(0);
        c.setLayoutData(fillH());
        // Scroll-guard — prevent accidental mouse-wheel selection changes
        c.addListener(SWT.MouseWheel, event -> {
            if (!c.isFocusControl()) event.doit = false;
        });
        return c;
    }

    private static void selectCombo(Combo combo, String value) {
        if (value == null) return;
        for (int i = 0; i < combo.getItemCount(); i++) {
            if (combo.getItem(i).equals(value)) {
                combo.select(i);
                return;
            }
        }
    }

    private static int indexOf(AIProvider p) {
        AIProvider[] all = AIProvider.values();
        for (int i = 0; i < all.length; i++) if (all[i] == p) return i;
        return 0;
    }
}
