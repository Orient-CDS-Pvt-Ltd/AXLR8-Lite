// Copyright 2026 OrientCDS Private Limited. Licensed under Apache 2.0.
package com.orient.axlr8lite.views;

import com.abapai.plugin.preferences.ABAPAIPreferences;
import com.abapai.plugin.services.ai.AIProvider;
import com.abapai.plugin.services.backend.BackendMode;
import com.abapai.plugin.services.backend.BackendModeService;
import com.abapai.plugin.services.context.FileContextService;
import com.abapai.plugin.services.context.FileContextService.FileContext;
import com.orient.axlr8lite.preferences.LitePreferences;
import com.orient.axlr8lite.services.LiteChatService;

import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.dialogs.PreferencesUtil;
import org.eclipse.ui.part.ViewPart;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * AXLR8 Lite Chat View — the only UI surface in Lite.
 *
 * <p>A single SWT view providing:
 *   <ul>
 *     <li>Provider dropdown (Claude Code / GitHub Models)</li>
 *     <li>SAP target dropdown (EHP8 / S/4HANA)</li>
 *     <li>Read-only chat transcript</li>
 *     <li>Multi-line input + Send button (Enter sends, Shift+Enter = newline)</li>
 *     <li>Toggle: Include active editor as context</li>
 *     <li>Clear conversation / Apply last code to editor / Open Settings</li>
 *     <li>Status bar with active-model label</li>
 *   </ul>
 *
 * <p>Bulk-of-Lite class — kept lean. No 17-mode dropdown, no deploy buttons,
 * no Apply-to-SAP, no AI Fix popup.
 */
public class LiteChatView extends ViewPart {

    public static final String VIEW_ID = "com.orient.axlr8lite.view.chat";
    private static final Logger LOG = Logger.getLogger(LiteChatView.class.getName());

    // Toolbar
    private Combo  providerCombo;
    private Combo  modeCombo;

    // Chat display
    private StyledText chatDisplay;
    private Font codeFont;

    // Input area
    private Text   inputField;
    private Button includeEditorCheck;
    private Button sendBtn;
    private Button stopBtn;
    private Button clearBtn;
    private Button applyEditorBtn;
    private Button copyCodeBtn;

    // Status
    private Label  statusLabel;

    // State
    private LiteChatService chatService;
    private String lastAssistantText = "";
    private volatile boolean busy = false;
    private volatile boolean cancelled = false;
    private int assistantBlockStart = -1;       // offset where current AI reply begins

    // Busy "thinking…" animation
    private boolean thinkingAnimating = false;
    private int     thinkingDots = 0;

    // Persistence keys (preference store)
    private static final String KEY_TRANSCRIPT = "lite.chat.transcript";
    private static final String KEY_HISTORY    = "lite.chat.history";
    private static final String KEY_INCLUDE_EDITOR_STATE = "lite.chat.include.editor.state";

    // Inline-markdown styling colours (lazily created, disposed in dispose())
    private Color codeBg;
    private Font  monoFont;

    // ─── Lifecycle ──────────────────────────────────────────────────

    @Override
    public void createPartControl(Composite parent) {
        chatService = new LiteChatService();
        codeFont = JFaceResources.getTextFont();

        Composite root = new Composite(parent, SWT.NONE);
        root.setLayout(new GridLayout(1, false));
        root.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        buildToolbar(root);
        buildChatDisplay(root);
        buildInputArea(root);
        buildStatusBar(root);

        loadInitialState();

        // Restore the previous session's transcript + conversation history,
        // if any. Otherwise show the ready banner.
        if (!restorePersistedSession()) {
            appendSystem("AXLR8 Lite ready. Ask anything about ABAP.");
        }

        // First-run flow — shown once per install:
        //   1. Registration form (analytics)
        //   2. Welcome / upsell popup
        // Sequenced (registration first, then welcome) so they don't stack.
        showFirstRunFlow();
    }

    /** First-run sequence: registration form, then the welcome upsell. Each is
     *  gated by its own one-time preference flag, so neither re-fires. */
    private void showFirstRunFlow() {
        org.eclipse.jface.preference.IPreferenceStore store =
            com.abapai.plugin.activator.Activator.getDefault().getPreferenceStore();

        Display.getDefault().asyncExec(() -> {
            // 1. Registration (modal — blocks until the user submits or skips)
            try {
                RegistrationDialog.showOnceOnFirstRun();
            } catch (Throwable t) {
                LOG.log(Level.WARNING, "Registration dialog failed: " + t.getMessage(), t);
            }
            // 2. Welcome upsell (only once)
            final String KEY_WELCOME_SHOWN = "lite.welcome.shown";
            if (!store.getBoolean(KEY_WELCOME_SHOWN)) {
                try {
                    com.orient.axlr8lite.views.UpsellDialog.showWelcome();
                } catch (Throwable t) {
                    LOG.log(Level.WARNING, "Welcome popup failed: " + t.getMessage(), t);
                }
                store.setValue(KEY_WELCOME_SHOWN, true);
            }
        });
    }

    @Override
    public void setFocus() {
        if (inputField != null && !inputField.isDisposed()) inputField.setFocus();
    }

    @Override
    public void dispose() {
        try {
            persistSession();
        } catch (Throwable ignore) {}
        try {
            if (chatService != null) chatService.cancel();
        } catch (Throwable ignore) {}
        if (codeBg != null && !codeBg.isDisposed()) codeBg.dispose();
        // monoFont comes from JFaceResources — do NOT dispose it.
        super.dispose();
    }

    // ─── Persistence ────────────────────────────────────────────────

    private org.eclipse.jface.preference.IPreferenceStore prefStore() {
        return com.abapai.plugin.activator.Activator.getDefault().getPreferenceStore();
    }

    /** Save the transcript + conversation history so the next open restores them. */
    private void persistSession() {
        if (chatDisplay == null || chatDisplay.isDisposed()) return;
        var store = prefStore();
        store.setValue(KEY_TRANSCRIPT, chatDisplay.getText());
        store.setValue(KEY_HISTORY, chatService != null ? chatService.exportConversation() : "");
        store.setValue(KEY_INCLUDE_EDITOR_STATE, includeEditorCheck.getSelection());
    }

    /** Restore a previously persisted session. Returns true if anything was restored. */
    private boolean restorePersistedSession() {
        var store = prefStore();
        String transcript = store.getString(KEY_TRANSCRIPT);
        if (transcript == null || transcript.isBlank()) return false;
        chatDisplay.setText(transcript);
        chatService.importConversation(store.getString(KEY_HISTORY));
        // Re-style restored content + enable code actions if a block is present.
        restyleAssistantRegion(0, chatDisplay.getCharCount());
        lastAssistantText = transcript; // best-effort for Apply/Copy after restore
        boolean hasCode = extractAbapBlock(transcript) != null;
        applyEditorBtn.setEnabled(hasCode);
        copyCodeBtn.setEnabled(hasCode);
        scrollToEnd();
        return true;
    }

    // ─── UI construction ────────────────────────────────────────────

    private void buildToolbar(Composite parent) {
        Composite bar = new Composite(parent, SWT.NONE);
        bar.setLayout(new GridLayout(8, false));
        bar.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        new Label(bar, SWT.NONE).setText("Provider:");
        providerCombo = new Combo(bar, SWT.READ_ONLY);
        for (AIProvider p : AIProvider.values()) providerCombo.add(p.getLabel());
        providerCombo.addSelectionListener(new SelectionAdapter() {
            @Override public void widgetSelected(SelectionEvent e) {
                int i = providerCombo.getSelectionIndex();
                if (i >= 0) {
                    LitePreferences.setProvider(AIProvider.values()[i]);
                    rebuildChatService();
                    refreshStatusBar();
                }
            }
        });

        new Label(bar, SWT.NONE).setText("    Target:");
        modeCombo = new Combo(bar, SWT.READ_ONLY);
        modeCombo.add("S/4HANA");
        modeCombo.add("EHP8");
        modeCombo.addSelectionListener(new SelectionAdapter() {
            @Override public void widgetSelected(SelectionEvent e) {
                BackendMode m = modeCombo.getSelectionIndex() == 1
                    ? BackendMode.EHP8
                    : BackendMode.S4HANA;
                BackendModeService.getInstance().setMode(m);
                appendSystem("Target switched to " + (m == BackendMode.EHP8 ? "EHP8" : "S/4HANA"));
            }
        });

        // Spacer
        Label spacer = new Label(bar, SWT.NONE);
        spacer.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        // Expand — opens the conversation in a large rich-rendered window
        Button expandBtn = new Button(bar, SWT.PUSH);
        expandBtn.setText("⛶ Expand");
        expandBtn.setToolTipText("Open the conversation in a large window with formatted markdown.");
        expandBtn.addSelectionListener(new SelectionAdapter() {
            @Override public void widgetSelected(SelectionEvent e) {
                String raw = (chatDisplay == null || chatDisplay.isDisposed())
                    ? "" : chatDisplay.getText();
                ExpandWindow.open(getSite().getShell(), raw);
            }
        });

        // Settings link
        Button settingsBtn = new Button(bar, SWT.PUSH);
        settingsBtn.setText("⚙ Settings");
        settingsBtn.addSelectionListener(new SelectionAdapter() {
            @Override public void widgetSelected(SelectionEvent e) {
                PreferencesUtil.createPreferenceDialogOn(
                    getSite().getShell(),
                    "com.orient.axlr8lite.preferences.page",
                    new String[]{"com.orient.axlr8lite.preferences.page"},
                    null).open();
                // Settings may have changed the provider / CLI path / PAT, so
                // rebuild the chat service against the new configuration —
                // otherwise the open view keeps using a backend created with
                // the old settings.
                rebuildChatService();
                // Reflect any provider change in the toolbar dropdown.
                providerCombo.select(indexOf(LitePreferences.getProvider()));
                refreshStatusBar();
            }
        });

        // Upgrade link (funnel CTA)
        Button upgradeBtn = new Button(bar, SWT.PUSH);
        upgradeBtn.setText("✦ Upgrade to Full");
        upgradeBtn.setToolTipText(
            "AXLR8 + ACTV8 Full adds: live SAP schemas, ACTV8 code generator, "
            + "safe deploy (EHP8 + S/4), AI Fix loops, TSD docs, 4 more LLM providers.");
        upgradeBtn.addSelectionListener(new SelectionAdapter() {
            @Override public void widgetSelected(SelectionEvent e) {
                // Take the user straight to the full plugin's Eclipse Marketplace
                // listing (single source of truth for the upsell URL —
                // UpsellDialog.MARKETPLACE_URL).
                openUrl(com.orient.axlr8lite.views.UpsellDialog.MARKETPLACE_URL);
            }
        });
    }

    private void buildChatDisplay(Composite parent) {
        chatDisplay = new StyledText(parent,
            SWT.MULTI | SWT.READ_ONLY | SWT.V_SCROLL | SWT.H_SCROLL | SWT.BORDER | SWT.WRAP);
        chatDisplay.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        chatDisplay.setFont(codeFont);
    }

    private void buildInputArea(Composite parent) {
        Composite area = new Composite(parent, SWT.NONE);
        area.setLayout(new GridLayout(1, false));
        area.setLayoutData(new GridData(SWT.FILL, SWT.BOTTOM, true, false));

        inputField = new Text(area,
            SWT.MULTI | SWT.BORDER | SWT.WRAP | SWT.V_SCROLL);
        GridData gd = new GridData(SWT.FILL, SWT.FILL, true, false);
        gd.heightHint = 80;
        inputField.setLayoutData(gd);
        inputField.addListener(SWT.KeyDown, ev -> {
            // Enter sends; Shift+Enter inserts a newline. (Ctrl+Enter also
            // sends, for users used to that convention.)
            if (ev.keyCode == SWT.CR || ev.keyCode == SWT.KEYPAD_CR) {
                if ((ev.stateMask & SWT.SHIFT) != 0) {
                    return; // Shift+Enter → let the newline through
                }
                ev.doit = false;
                onSend();
            }
        });

        Composite actions = new Composite(area, SWT.NONE);
        actions.setLayout(new GridLayout(8, false));
        actions.setLayoutData(new GridData(SWT.FILL, SWT.BOTTOM, true, false));

        includeEditorCheck = new Button(actions, SWT.CHECK);
        includeEditorCheck.setText("Editor");
        includeEditorCheck.setToolTipText("Include the active editor file as chat context.");
        includeEditorCheck.addSelectionListener(new SelectionAdapter() {
            @Override public void widgetSelected(SelectionEvent e) {
                // Remember the toggle across sessions
                LitePreferences.setIncludeEditorByDefault(includeEditorCheck.getSelection());
            }
        });

        // Spacer
        Label spacer = new Label(actions, SWT.NONE);
        spacer.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        clearBtn = new Button(actions, SWT.PUSH);
        clearBtn.setText("Clear");
        clearBtn.addSelectionListener(new SelectionAdapter() {
            @Override public void widgetSelected(SelectionEvent e) { onClear(); }
        });

        copyCodeBtn = new Button(actions, SWT.PUSH);
        copyCodeBtn.setText("Copy Code");
        copyCodeBtn.setToolTipText("Copy the last code block from the AI response to the clipboard.");
        copyCodeBtn.setEnabled(false);
        copyCodeBtn.addSelectionListener(new SelectionAdapter() {
            @Override public void widgetSelected(SelectionEvent e) { onCopyCode(); }
        });

        applyEditorBtn = new Button(actions, SWT.PUSH);
        applyEditorBtn.setText("Apply to Editor");
        applyEditorBtn.setToolTipText("Write the last code block from the AI response into the active editor.");
        applyEditorBtn.setEnabled(false);
        applyEditorBtn.addSelectionListener(new SelectionAdapter() {
            @Override public void widgetSelected(SelectionEvent e) { onApplyToEditor(); }
        });

        stopBtn = new Button(actions, SWT.PUSH);
        stopBtn.setText("Stop");
        stopBtn.setToolTipText("Cancel the in-progress AI request.");
        stopBtn.setEnabled(false);
        stopBtn.addSelectionListener(new SelectionAdapter() {
            @Override public void widgetSelected(SelectionEvent e) { onStop(); }
        });

        sendBtn = new Button(actions, SWT.PUSH);
        sendBtn.setText("Send (↵)");
        sendBtn.setToolTipText("Send (Enter). Use Shift+Enter for a new line.");
        sendBtn.addSelectionListener(new SelectionAdapter() {
            @Override public void widgetSelected(SelectionEvent e) { onSend(); }
        });
    }

    private void buildStatusBar(Composite parent) {
        statusLabel = new Label(parent, SWT.NONE);
        statusLabel.setLayoutData(new GridData(SWT.FILL, SWT.BOTTOM, true, false));
    }

    // ─── State load ─────────────────────────────────────────────────

    private void loadInitialState() {
        // Provider
        AIProvider p = LitePreferences.getProvider();
        providerCombo.select(indexOf(p));

        // Mode
        modeCombo.select(LitePreferences.isEhp8Mode() ? 1 : 0);

        // Toggles
        includeEditorCheck.setSelection(LitePreferences.isIncludeEditorByDefault());

        refreshStatusBar();
    }

    private void refreshStatusBar() {
        try {
            statusLabel.setText("Ready — " + ABAPAIPreferences.getActiveModelLabel());
        } catch (Throwable t) {
            statusLabel.setText("Ready");
        }
    }

    /** Recreate the chat service so it picks up the latest provider / CLI
     *  path / PAT from preferences. The backend reads its configuration once
     *  at construction, so settings changes only take effect on a fresh
     *  instance. Preserves the running conversation history is intentionally
     *  NOT done — a provider switch starts a clean session. */
    private void rebuildChatService() {
        com.abapai.plugin.services.ai.AIServiceFactory.reset();
        if (chatService != null) {
            try { chatService.cancel(); } catch (Throwable ignore) {}
        }
        chatService = new LiteChatService();
    }

    // ─── Actions ────────────────────────────────────────────────────

    private void onSend() {
        if (busy) return;
        String text = inputField.getText().trim();
        if (text.isEmpty()) return;

        boolean includeEditor = includeEditorCheck.getSelection();

        // Capture the active editor content HERE, on the UI thread. The
        // workbench APIs used by FileContextService return null off the UI
        // thread, so this must happen before we spawn the worker below and
        // be passed in as a value.
        final FileContext editorContext = includeEditor
            ? FileContextService.getInstance().getCurrent()
            : null;

        appendUser(text);
        inputField.setText("");
        appendAssistantHeader();
        assistantBlockStart = chatDisplay.getCharCount(); // reply body starts here

        cancelled = false;
        setBusy(true);

        new Thread(() -> {
            try {
                String full = chatService.send(text, editorContext,
                    token -> appendAssistantToken(token));
                lastAssistantText = full == null ? "" : full;
                runOnUi(() -> {
                    if (cancelled) return;        // user pressed Stop — leave as-is
                    appendNewline();
                    // Re-style the AI reply: monospace + bg for code, bold for **text**.
                    restyleAssistantRegion(assistantBlockStart, chatDisplay.getCharCount());
                    boolean hasCode = extractAbapBlock(lastAssistantText) != null;
                    applyEditorBtn.setEnabled(hasCode);
                    copyCodeBtn.setEnabled(hasCode);
                    setBusy(false);
                });
            } catch (Exception ex) {
                LOG.log(Level.WARNING, "Chat send failed: " + ex.getMessage(), ex);
                runOnUi(() -> {
                    if (!cancelled) appendSystem("Error: " + ex.getMessage());
                    setBusy(false);
                });
            }
        }, "axlr8-lite-chat-send").start();
    }

    private void onStop() {
        if (!busy) return;
        cancelled = true;
        try { chatService.cancel(); } catch (Throwable ignore) {}
        appendSystem("Request cancelled.");
        setBusy(false);
    }

    private void onCopyCode() {
        String code = extractAbapBlock(lastAssistantText);
        if (code == null) return;
        org.eclipse.swt.dnd.Clipboard cb =
            new org.eclipse.swt.dnd.Clipboard(chatDisplay.getDisplay());
        try {
            cb.setContents(
                new Object[]{code},
                new org.eclipse.swt.dnd.Transfer[]{
                    org.eclipse.swt.dnd.TextTransfer.getInstance()});
        } finally {
            cb.dispose();
        }
        statusLabel.setText("Code block copied to clipboard.");
    }

    private void onClear() {
        chatDisplay.setText("");
        chatService.clearConversation();
        lastAssistantText = "";
        applyEditorBtn.setEnabled(false);
        copyCodeBtn.setEnabled(false);
        // Wipe persisted session so a reopen starts fresh.
        prefStore().setValue(KEY_TRANSCRIPT, "");
        prefStore().setValue(KEY_HISTORY, "");
        appendSystem("Conversation cleared.");
    }

    private void onApplyToEditor() {
        String code = extractAbapBlock(lastAssistantText);
        if (code == null) {
            MessageDialog.openInformation(getSite().getShell(), "Apply to Editor",
                "No ABAP code block found in the last AI response.");
            return;
        }
        boolean written = FileContextService.getInstance().applyToEditorReplaceAll(code);
        if (!written) {
            MessageDialog.openInformation(getSite().getShell(), "Apply to Editor",
                "Couldn't write to the active editor. Open an ABAP file first.");
        }
    }

    // ─── Chat display helpers ───────────────────────────────────────

    private void appendUser(String text) {
        int start = chatDisplay.getCharCount();
        chatDisplay.append("\nYou: ");
        addStyle(start, 5, SWT.BOLD);
        chatDisplay.append(text + "\n");
        scrollToEnd();
    }

    private void appendAssistantHeader() {
        int start = chatDisplay.getCharCount();
        chatDisplay.append("\nAXLR8: ");
        addStyle(start, 7, SWT.BOLD);
        scrollToEnd();
    }

    private void appendAssistantToken(String token) {
        runOnUi(() -> {
            chatDisplay.append(token);
            scrollToEnd();
        });
    }

    private void appendNewline() {
        chatDisplay.append("\n");
        scrollToEnd();
    }

    private void appendSystem(String text) {
        int start = chatDisplay.getCharCount();
        chatDisplay.append("[" + text + "]\n");
        StyleRange sr = new StyleRange();
        sr.start = start;
        sr.length = text.length() + 2;
        sr.foreground = chatDisplay.getDisplay().getSystemColor(SWT.COLOR_GRAY);
        chatDisplay.setStyleRange(sr);
        scrollToEnd();
    }

    private void addStyle(int start, int length, int style) {
        StyleRange sr = new StyleRange();
        sr.start = start;
        sr.length = length;
        sr.fontStyle = style;
        chatDisplay.setStyleRange(sr);
    }

    private void scrollToEnd() {
        if (chatDisplay.getCharCount() > 0) {
            chatDisplay.setTopIndex(chatDisplay.getLineCount() - 1);
        }
    }

    private void setBusy(boolean b) {
        busy = b;
        sendBtn.setEnabled(!b);
        clearBtn.setEnabled(!b);
        stopBtn.setEnabled(b);
        // Send hides while busy; Stop takes its place visually.
        sendBtn.setVisible(!b);
        stopBtn.setVisible(b);
        if (b) {
            startThinkingAnimation();
        } else {
            stopThinkingAnimation();
            statusLabel.setText("Ready — " + safeActiveModelLabel());
        }
    }

    // ─── Busy "thinking…" animation ─────────────────────────────────

    private void startThinkingAnimation() {
        thinkingAnimating = true;
        thinkingDots = 0;
        animateThinkingTick();
    }

    private void stopThinkingAnimation() {
        thinkingAnimating = false;
    }

    private void animateThinkingTick() {
        if (!thinkingAnimating || statusLabel == null || statusLabel.isDisposed()) return;
        String dots = ".".repeat(1 + (thinkingDots % 3));
        statusLabel.setText("AXLR8 is thinking" + dots);
        thinkingDots++;
        Display d = statusLabel.getDisplay();
        if (d != null && !d.isDisposed()) {
            d.timerExec(450, this::animateThinkingTick);
        }
    }

    private String safeActiveModelLabel() {
        try { return ABAPAIPreferences.getActiveModelLabel(); }
        catch (Throwable t) { return "ready"; }
    }

    // ─── Inline markdown styling (post-completion restyle) ──────────

    /** Apply lightweight markdown styling to the [start,end) region of the
     *  chat display: fenced ```code``` and inline `code` get a monospace font
     *  + light background; **bold** gets bold weight. Best-effort, never throws. */
    private void restyleAssistantRegion(int start, int end) {
        if (chatDisplay == null || chatDisplay.isDisposed()) return;
        if (start < 0 || end <= start || end > chatDisplay.getCharCount()) return;
        try {
            ensureStyleResources();
            String region = chatDisplay.getText(start, end - 1);

            // Fenced code blocks: ```...```
            java.util.regex.Matcher fence = java.util.regex.Pattern.compile(
                "```[a-zA-Z0-9]*\\r?\\n([\\s\\S]*?)```").matcher(region);
            while (fence.find()) {
                int s = start + fence.start();
                int len = fence.end() - fence.start();
                StyleRange sr = new StyleRange();
                sr.start = s; sr.length = len;
                sr.font = monoFont;
                sr.background = codeBg;
                chatDisplay.setStyleRange(sr);
            }

            // Inline code: `...`  (skip if inside a fence — fences already styled,
            // re-styling is harmless since later ranges override earlier ones)
            java.util.regex.Matcher inline = java.util.regex.Pattern.compile(
                "`([^`\\n]+)`").matcher(region);
            while (inline.find()) {
                int s = start + inline.start();
                int len = inline.end() - inline.start();
                StyleRange sr = new StyleRange();
                sr.start = s; sr.length = len;
                sr.font = monoFont;
                sr.background = codeBg;
                chatDisplay.setStyleRange(sr);
            }

            // Bold: **...**
            java.util.regex.Matcher bold = java.util.regex.Pattern.compile(
                "\\*\\*([^*\\n]+)\\*\\*").matcher(region);
            while (bold.find()) {
                int s = start + bold.start();
                int len = bold.end() - bold.start();
                StyleRange sr = new StyleRange();
                sr.start = s; sr.length = len;
                sr.fontStyle = SWT.BOLD;
                chatDisplay.setStyleRange(sr);
            }
        } catch (Throwable t) {
            LOG.log(Level.FINE, "restyle skipped: " + t.getMessage());
        }
    }

    private void ensureStyleResources() {
        if (monoFont == null) monoFont = JFaceResources.getTextFont();
        if (codeBg == null || codeBg.isDisposed()) {
            codeBg = new Color(chatDisplay.getDisplay(), 241, 243, 245); // light grey
        }
    }

    // ─── Utility ────────────────────────────────────────────────────

    /** Extract the first ```abap ... ``` block from an AI response. */
    private static String extractAbapBlock(String text) {
        if (text == null || text.isBlank()) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
            "```(?:abap)?\\s*\\n([\\s\\S]*?)\\n```",
            java.util.regex.Pattern.CASE_INSENSITIVE).matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private static int indexOf(AIProvider p) {
        AIProvider[] all = AIProvider.values();
        for (int i = 0; i < all.length; i++) if (all[i] == p) return i;
        return 0;
    }

    private void runOnUi(Runnable r) {
        Display d = chatDisplay.getDisplay();
        if (d != null && !d.isDisposed()) d.asyncExec(r);
    }

    private void openUrl(String url) {
        try {
            PlatformUI.getWorkbench().getBrowserSupport()
                .getExternalBrowser().openURL(new java.net.URI(url).toURL());
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to open URL " + url, e);
        }
    }
}
