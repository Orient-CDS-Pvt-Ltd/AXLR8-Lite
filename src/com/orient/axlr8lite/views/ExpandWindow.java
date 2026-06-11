// Copyright 2026 OrientCDS Private Limited. Licensed under Apache 2.0.
package com.orient.axlr8lite.views;

import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;

import java.util.ArrayList;
import java.util.List;

/**
 * Expanded conversation window.
 *
 * <p>Opens the running chat transcript in a large, resizable window with the
 * markdown rendered to polished HTML (headings, bold/italic, inline + fenced
 * code, lists, tables, horizontal rules, and coloured role labels). Falls back
 * to a plain {@link StyledText} if the platform can't provide an SWT
 * {@link Browser}. Copy + Close buttons; Esc closes.
 *
 * <p>Self-contained — no external dependencies beyond SWT.
 */
public final class ExpandWindow {

    private ExpandWindow() {} // static entry only

    /** Open the expanded window with the given raw conversation transcript. */
    public static void open(Shell parent, String rawText) {
        if (parent == null || parent.isDisposed()) return;
        final String raw = rawText == null ? "" : rawText;

        Shell shell = new Shell(parent,
            SWT.DIALOG_TRIM | SWT.RESIZE | SWT.MAX | SWT.MIN | SWT.MODELESS);
        shell.setText("AXLR8 Lite — Conversation");
        shell.setLayout(new GridLayout(1, false));
        shell.setSize(1100, 780);

        // Rich HTML via Browser; graceful fallback to StyledText.
        Browser browser = null;
        try {
            browser = new Browser(shell, SWT.NONE);
            browser.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
            browser.setText(buildHtml(raw));
        } catch (Throwable browserInitError) {
            if (browser != null && !browser.isDisposed()) browser.dispose();
            StyledText fallback = new StyledText(shell,
                SWT.MULTI | SWT.READ_ONLY | SWT.BORDER | SWT.V_SCROLL | SWT.WRAP);
            fallback.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
            fallback.setLeftMargin(10);
            fallback.setTopMargin(8);
            fallback.setRightMargin(10);
            fallback.setBottomMargin(8);
            fallback.setLineSpacing(2);
            fallback.setWordWrap(true);
            fallback.setText(raw);
        }

        Composite actions = new Composite(shell, SWT.NONE);
        actions.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        actions.setLayout(new GridLayout(3, false));

        Label filler = new Label(actions, SWT.NONE);
        filler.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Button copyBtn = new Button(actions, SWT.PUSH);
        copyBtn.setText("Copy");
        copyBtn.setToolTipText("Copy the raw conversation text to the clipboard.");
        copyBtn.addListener(SWT.Selection, e -> {
            Clipboard clipboard = new Clipboard(shell.getDisplay());
            try {
                clipboard.setContents(
                    new Object[]{raw},
                    new Transfer[]{TextTransfer.getInstance()});
            } finally {
                clipboard.dispose();
            }
        });

        Button closeBtn = new Button(actions, SWT.PUSH);
        closeBtn.setText("Close");
        closeBtn.addListener(SWT.Selection, e -> shell.close());

        shell.addListener(SWT.Traverse, e -> {
            if (e.detail == SWT.TRAVERSE_ESCAPE) {
                e.doit = false;
                shell.close();
            }
        });

        shell.open();
    }

    // ─── HTML shell ─────────────────────────────────────────────────

    private static String buildHtml(String raw) {
        return "<!DOCTYPE html><html lang=\"en\"><head>"
            + "<meta charset=\"utf-8\">"
            + "<style>" + CSS + "</style>"
            + "</head><body onload=\"window.scrollTo(0,document.body.scrollHeight);\">"
            + "<div class=\"chat\">"
            + markdownToHtml(raw)
            + "</div></body></html>";
    }

    private static final String CSS =
        "html,body{margin:0;padding:0;}"
        + "body{background:#ffffff;color:#1d2329;"
        + "font-family:'Segoe UI',Helvetica,Arial,sans-serif;"
        + "font-size:13px;line-height:1.55;}"
        + ".chat{max-width:960px;margin:0 auto;padding:18px 24px 80px;}"
        + "h1,h2,h3,h4{color:#0f1c2e;margin:1.4em 0 0.5em;font-weight:600;line-height:1.3;}"
        + "h1{font-size:1.5em;border-bottom:1px solid #dfe4ea;padding-bottom:6px;}"
        + "h2{font-size:1.25em;border-bottom:1px solid #eceff3;padding-bottom:4px;}"
        + "h3{font-size:1.1em;}h4{font-size:1em;}"
        + "p{margin:0.6em 0;}"
        + "strong{color:#0f1c2e;}em{color:#374151;}"
        + "code{background:#f1f3f5;color:#c2185b;"
        + "font-family:Consolas,'Courier New',monospace;font-size:0.92em;"
        + "padding:1px 5px;border-radius:3px;}"
        + "pre{background:#0f1c2e;color:#e5edf4;"
        + "font-family:Consolas,'Courier New',monospace;font-size:0.9em;"
        + "padding:12px 14px;border-radius:5px;"
        + "overflow-x:auto;line-height:1.45;margin:0.7em 0;white-space:pre;}"
        + "pre code{background:transparent;color:inherit;padding:0;font-size:inherit;}"
        + "ul,ol{margin:0.5em 0 0.6em 1.6em;padding:0;}"
        + "li{margin:0.25em 0;}"
        + "hr{border:0;border-top:1px solid #dfe4ea;margin:1.3em 0;}"
        + "table{border-collapse:collapse;margin:0.8em 0;"
        + "border:1px solid #c8cdd4;width:auto;}"
        + "th,td{border:1px solid #c8cdd4;padding:5px 10px;text-align:left;"
        + "vertical-align:top;}"
        + "th{background:#eef2f6;font-weight:600;}"
        + "tr:nth-child(even) td{background:#f8fafb;}"
        + ".role{font-weight:600;font-size:0.8em;letter-spacing:0.04em;"
        + "text-transform:uppercase;margin-top:1.4em;padding:6px 10px;"
        + "border-radius:3px;display:inline-block;}"
        + ".role-you{background:#e3f2fd;color:#0d47a1;}"
        + ".role-ai{background:#ede7f6;color:#4527a0;}"
        + ".role-info{background:#e8f5e9;color:#1b5e20;}"
        + ".role-system{background:#fff3e0;color:#b26a00;}"
        + ".bubble{margin:0 0 0.3em 0;padding:0;}";

    // ─── Markdown → HTML ────────────────────────────────────────────

    private static String markdownToHtml(String md) {
        if (md == null || md.isEmpty()) return "";
        String[] lines = md.replace("\r\n", "\n").replace("\r", "\n").split("\n", -1);

        StringBuilder out = new StringBuilder();
        boolean inCodeFence = false;
        StringBuilder codeBuf = new StringBuilder();
        List<String> paraBuf = new ArrayList<>();
        List<String> listBuf = new ArrayList<>();
        String listType = null;
        List<String> tableBuf = new ArrayList<>();

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];

            if (line.trim().startsWith("```")) {
                if (inCodeFence) {
                    out.append("<pre><code>")
                       .append(escHtml(codeBuf.toString()))
                       .append("</code></pre>");
                    codeBuf.setLength(0);
                    inCodeFence = false;
                } else {
                    flushParagraph(out, paraBuf);
                    flushList(out, listBuf, listType); listType = null;
                    flushTable(out, tableBuf);
                    inCodeFence = true;
                }
                continue;
            }
            if (inCodeFence) {
                if (codeBuf.length() > 0) codeBuf.append('\n');
                codeBuf.append(line);
                continue;
            }

            String trimmed = line.trim();

            if (trimmed.isEmpty()) {
                flushParagraph(out, paraBuf);
                flushList(out, listBuf, listType); listType = null;
                flushTable(out, tableBuf);
                continue;
            }

            String roleClass = detectRoleMarker(trimmed);
            if (roleClass != null) {
                flushParagraph(out, paraBuf);
                flushList(out, listBuf, listType); listType = null;
                flushTable(out, tableBuf);
                int colon = trimmed.indexOf(':');
                String label = colon > 0 ? trimmed.substring(0, colon) : trimmed;
                String rest = colon > 0 && colon + 1 < trimmed.length()
                    ? trimmed.substring(colon + 1).trim() : "";
                out.append("<div class=\"role ").append(roleClass).append("\">")
                   .append(escHtml(label)).append("</div>");
                if (!rest.isEmpty()) paraBuf.add(rest);
                continue;
            }

            if (trimmed.startsWith("#")) {
                int level = 0;
                while (level < trimmed.length() && trimmed.charAt(level) == '#') level++;
                if (level >= 1 && level <= 4 && level < trimmed.length()
                        && trimmed.charAt(level) == ' ') {
                    flushParagraph(out, paraBuf);
                    flushList(out, listBuf, listType); listType = null;
                    flushTable(out, tableBuf);
                    String text = trimmed.substring(level + 1).trim();
                    out.append("<h").append(level).append(">")
                       .append(inlineFormat(text))
                       .append("</h").append(level).append(">");
                    continue;
                }
            }

            if (trimmed.matches("[-*_]{3,}")) {
                flushParagraph(out, paraBuf);
                flushList(out, listBuf, listType); listType = null;
                flushTable(out, tableBuf);
                out.append("<hr>");
                continue;
            }

            if (trimmed.contains("|")) {
                flushParagraph(out, paraBuf);
                flushList(out, listBuf, listType); listType = null;
                tableBuf.add(trimmed);
                boolean nextIsTable = i + 1 < lines.length
                    && lines[i + 1].trim().contains("|")
                    && !lines[i + 1].trim().isEmpty();
                if (!nextIsTable) flushTable(out, tableBuf);
                continue;
            }

            if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
                flushParagraph(out, paraBuf);
                flushTable(out, tableBuf);
                if (!"ul".equals(listType)) {
                    flushList(out, listBuf, listType);
                    listType = "ul";
                }
                listBuf.add(trimmed.substring(2).trim());
                continue;
            }

            if (trimmed.matches("^\\d+\\.\\s.*")) {
                flushParagraph(out, paraBuf);
                flushTable(out, tableBuf);
                if (!"ol".equals(listType)) {
                    flushList(out, listBuf, listType);
                    listType = "ol";
                }
                int dot = trimmed.indexOf('.');
                listBuf.add(trimmed.substring(dot + 1).trim());
                continue;
            }

            flushList(out, listBuf, listType); listType = null;
            flushTable(out, tableBuf);
            paraBuf.add(line);
        }

        if (inCodeFence) {
            out.append("<pre><code>")
               .append(escHtml(codeBuf.toString()))
               .append("</code></pre>");
        }
        flushParagraph(out, paraBuf);
        flushList(out, listBuf, listType);
        flushTable(out, tableBuf);
        return out.toString();
    }

    private static String detectRoleMarker(String line) {
        if (line.startsWith("You:"))      return "role-you";
        if (line.startsWith("AXLR8:"))    return "role-ai";
        if (line.startsWith("Info:"))     return "role-info";
        if (line.startsWith("Warning:") || line.startsWith("Error:")) return "role-system";
        if (line.startsWith("[") )        return "role-system"; // [System ...] status lines
        return null;
    }

    private static void flushParagraph(StringBuilder out, List<String> buf) {
        if (buf.isEmpty()) return;
        out.append("<p class=\"bubble\">")
           .append(inlineFormat(String.join(" ", buf)))
           .append("</p>");
        buf.clear();
    }

    private static void flushList(StringBuilder out, List<String> buf, String type) {
        if (buf.isEmpty() || type == null) { buf.clear(); return; }
        out.append("<").append(type).append(">");
        for (String item : buf) {
            out.append("<li>").append(inlineFormat(item)).append("</li>");
        }
        out.append("</").append(type).append(">");
        buf.clear();
    }

    private static void flushTable(StringBuilder out, List<String> buf) {
        if (buf.isEmpty()) return;
        List<String[]> rows = new ArrayList<>();
        int separatorIdx = -1;
        for (int r = 0; r < buf.size(); r++) {
            String row = buf.get(r).trim();
            if (row.startsWith("|")) row = row.substring(1);
            if (row.endsWith("|")) row = row.substring(0, row.length() - 1);
            if (row.replaceAll("[-:\\|\\s]", "").isEmpty() && separatorIdx < 0) {
                separatorIdx = r;
                continue;
            }
            String[] cells = row.split("\\|", -1);
            for (int c = 0; c < cells.length; c++) cells[c] = cells[c].trim();
            rows.add(cells);
        }
        if (rows.isEmpty()) { buf.clear(); return; }
        out.append("<table>");
        boolean headerUsed = false;
        for (int r = 0; r < rows.size(); r++) {
            String[] cells = rows.get(r);
            out.append("<tr>");
            boolean asHeader = !headerUsed && separatorIdx > 0 && r == 0;
            if (asHeader) headerUsed = true;
            String tag = asHeader ? "th" : "td";
            for (String c : cells) {
                out.append("<").append(tag).append(">")
                   .append(inlineFormat(c))
                   .append("</").append(tag).append(">");
            }
            out.append("</tr>");
        }
        out.append("</table>");
        buf.clear();
    }

    private static String inlineFormat(String text) {
        if (text == null || text.isEmpty()) return "";
        String esc = escHtml(text);
        esc = esc.replaceAll("`([^`]+)`", "<code>$1</code>");
        esc = esc.replaceAll("\\*\\*([^*]+)\\*\\*", "<strong>$1</strong>");
        esc = esc.replaceAll("(?<![*\\w])\\*([^*\\s][^*]*)\\*(?![*\\w])", "<em>$1</em>");
        return esc;
    }

    private static String escHtml(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
