// Copyright 2026 OrientCDS Private Limited. Licensed under Apache 2.0.
package com.abapai.plugin.services.context;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.part.MultiPageEditorPart;
import org.eclipse.ui.texteditor.ITextEditor;

/**
 * FileContextService — reads the active editor's content and selection.
 * Used to inject file context into AI prompts (Codex/Claude Code style).
 * All public methods must be called from the UI thread.
 */
public class FileContextService {

    private static final Logger LOG = Logger.getLogger(FileContextService.class.getName());

    public record EditorLocation(String fileName, String projectName, String resourcePath) {
        public static final EditorLocation EMPTY = new EditorLocation("", "", "");
        public boolean hasResourcePath() {
            return resourcePath != null && !resourcePath.isBlank();
        }
    }

    public record FileContext(String fileName, String fullContent,
                              String selectedText, int cursorLine) {

        public static final FileContext EMPTY = new FileContext("", "", "", 0);

        public boolean hasSelection() {
            return selectedText != null && !selectedText.isBlank();
        }

        public boolean hasContent() {
            return fullContent != null && !fullContent.isBlank();
        }

        /** Formats file content as a prompt prefix ready to prepend to the user's request. */
        public String toPromptPrefix() {
            if (!hasContent() && !hasSelection()) return "";
            StringBuilder sb = new StringBuilder();
            sb.append("=== Current ABAP File: ").append(fileName).append(" ===\n");
            if (hasSelection()) {
                sb.append("Selected code (around line ").append(cursorLine).append("):\n```abap\n")
                  .append(selectedText).append("\n```\n\n");
            } else {
                sb.append("```abap\n").append(fullContent).append("\n```\n\n");
            }
            sb.append("=== Your Request ===\n");
            return sb.toString();
        }
    }

    private static final FileContextService INSTANCE = new FileContextService();
    public static FileContextService getInstance() { return INSTANCE; }
    private FileContextService() {}

    /** Returns the current editor state. Returns FileContext.EMPTY if no text editor is open. */
    public FileContext getCurrent() {
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if (window == null || window.getActivePage() == null) return FileContext.EMPTY;
        IEditorPart editor = window.getActivePage().getActiveEditor();
        if (editor == null) return FileContext.EMPTY;

        ITextEditor te = findTextEditor(editor);
        if (te == null) return FileContext.EMPTY;

        try {
            IDocument doc = te.getDocumentProvider().getDocument(te.getEditorInput());
            ITextSelection sel = (ITextSelection) te.getSelectionProvider().getSelection();
            String fileName    = te.getEditorInput().getName();
            String fullContent = (doc != null) ? doc.get() : "";
            String selected    = (sel != null && sel.getLength() > 0) ? sel.getText() : "";
            int    line        = (sel != null) ? sel.getStartLine() + 1 : 0;
            return new FileContext(fileName, fullContent, selected, line);
        } catch (Exception ex) {
            return FileContext.EMPTY;
        }
    }

    public EditorLocation getCurrentEditorLocation() {
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if (window == null || window.getActivePage() == null) return EditorLocation.EMPTY;
        IEditorPart editor = window.getActivePage().getActiveEditor();
        if (editor == null) return EditorLocation.EMPTY;

        ITextEditor te = findTextEditor(editor);
        if (te == null) return EditorLocation.EMPTY;

        try {
            IEditorInput input = te.getEditorInput();
            String fileName = input.getName();
            IResource resource = resolveResource(input);
            String projectName = resolveProjectName(input, resource);
            String path = resource != null ? resource.getFullPath().toString() : "";

            // Cloud/remote ADT editors sometimes expose project but not workspace path.
            if ((path == null || path.isBlank())
                && projectName != null && !projectName.isBlank()
                && fileName != null && !fileName.isBlank()) {
                path = "/" + projectName + "/" + fileName;
            }

            return new EditorLocation(fileName != null ? fileName : "", projectName, path);
        } catch (Exception ex) {
            return EditorLocation.EMPTY;
        }
    }

    private IResource resolveResource(IEditorInput input) {
        if (input == null) return null;
        if (input instanceof IFileEditorInput fileInput) {
            return fileInput.getFile();
        }

        IResource adaptedResource = input.getAdapter(IResource.class);
        if (adaptedResource != null) return adaptedResource;

        org.eclipse.core.resources.IFile adaptedFile =
            input.getAdapter(org.eclipse.core.resources.IFile.class);
        if (adaptedFile != null) return adaptedFile;

        // ADT editor inputs can expose resource/file via non-interface methods.
        Object byFile = invokeNoArg(input, "getFile");
        if (byFile instanceof IResource resourceFromFile) return resourceFromFile;

        Object byResource = invokeNoArg(input, "getResource");
        if (byResource instanceof IResource resourceFromMethod) return resourceFromMethod;

        return null;
    }

    private String resolveProjectName(IEditorInput input, IResource resource) {
        if (resource != null && resource.getProject() != null) {
            return resource.getProject().getName();
        }

        IProject adaptedProject = input != null ? input.getAdapter(IProject.class) : null;
        if (adaptedProject != null) return adaptedProject.getName();

        Object byProject = invokeNoArg(input, "getProject");
        if (byProject instanceof IProject project) return project.getName();

        String tooltip = input != null ? input.getToolTipText() : "";
        if (tooltip != null && !tooltip.isBlank()) {
            // Try workspace-style path: /ProjectName/...
            java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(^|\\s)/(?!sap/)([^/\\s]+)/(?:.*)")
                .matcher(tooltip);
            if (m.find()) {
                String candidate = m.group(2);
                if (candidate != null && !candidate.isBlank()) return candidate;
            }

            // Try ADT URI-style hints containing a project token.
            String upper = tooltip.toUpperCase(Locale.ROOT);
            m = java.util.regex.Pattern
                .compile("([A-Z0-9]{3}_[0-9]{3}_[A-Z0-9_]+)")
                .matcher(upper);
            if (m.find()) return m.group(1);
        }

        return "";
    }

    private Object invokeNoArg(Object target, String methodName) {
        if (target == null || methodName == null || methodName.isBlank()) return null;
        try {
            Method method = target.getClass().getMethod(methodName);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (Exception ignored) {
            return null;
        }
    }

    public String inferCurrentPackageName() {
        EditorLocation loc = getCurrentEditorLocation();
        if (!loc.hasResourcePath()) return "";

        String[] rawSegments = loc.resourcePath().split("[/\\\\]+");
        String activeObject = loc.fileName() != null
            ? loc.fileName().trim().toUpperCase().replaceFirst("\\..*$", "")
            : "";

        for (int i = 0; i < rawSegments.length; i++) {
            String seg = normalizePathSegment(rawSegments[i]);
            if (seg.isBlank()) continue;

            if (isLibraryMarker(seg) && i > 0) {
                String candidate = normalizePathSegment(rawSegments[i - 1]);
                if (looksLikePackage(candidate) && !looksLikeActiveObjectSegment(candidate, activeObject)) {
                    return candidate;
                }
            }
        }

        for (String raw : rawSegments) {
            String candidate = normalizePathSegment(raw);
            if (looksLikePackage(candidate) && !looksLikeActiveObjectSegment(candidate, activeObject)) {
                return candidate;
            }
        }
        return "";
    }

    private String normalizePathSegment(String raw) {
        if (raw == null) return "";
        String upper = raw.trim().toUpperCase();
        if (upper.isBlank()) return "";
        int paren = upper.indexOf(" (");
        if (paren > 0) {
            upper = upper.substring(0, paren).trim();
        }
        return upper.replaceAll("[^A-Z0-9_$]", "");
    }

    private boolean looksLikePackage(String candidate) {
        if (candidate == null || candidate.isBlank()) return false;
        if ("$TMP".equals(candidate)) return true;
        return candidate.matches("[ZY][A-Z0-9_]{1,29}");
    }

    private boolean looksLikeActiveObjectSegment(String candidate, String activeObject) {
        if (candidate == null || candidate.isBlank()) return false;
        if (activeObject == null || activeObject.isBlank()) return false;
        if (candidate.equals(activeObject)) return true;
        if (candidate.contains(activeObject)) return true;
        return candidate.matches("(?i)(ZCL_|ZIF_|ZCX_|ZFG_|ZTEST_).+");
    }

    private boolean isLibraryMarker(String segment) {
        return switch (segment) {
            case "SYSTEMLIBRARY",
                 "FAVORITEPACKAGES",
                 "LOCALOBJECTS",
                 "DICTIONARY",
                 "SOURCECODELIBRARY",
                 "CLASSES",
                 "INTERFACES",
                 "PROGRAMS",
                 "REPORTS",
                 "FUNCTIONGROUPS",
                 "FUNCTIONMODULES",
                 "STRUCTURES",
                 "TABLES",
                 "DOMAINS",
                 "DATAELEMENTS",
                 "TABLETYPES",
                 "MESSAGES",
                 "TRANSFORMATIONS",
                 "BUSINESSSERVICES",
                 "COREDATASERVICES",
                 "TEXTS",
                 "NUMBERRANGEMANAGEMENT" -> true;
            default -> false;
        };
    }

    /**
     * Extracts an ITextEditor from any editor part.
     * ADT's ABAP editor is a MultiPageEditorPart whose active page is the
     * actual text editor — getAdapter() alone doesn't always reach it.
     */
    private ITextEditor findTextEditor(IEditorPart editor) {
        if (editor instanceof ITextEditor te) return te;

        // Standard adapter — works for some multi-page editors
        ITextEditor adapted = editor.getAdapter(ITextEditor.class);
        if (adapted != null) return adapted;

        // ADT multi-page editors: call protected getActiveEditor() via reflection
        if (editor instanceof MultiPageEditorPart) {
            try {
                Method m = MultiPageEditorPart.class.getDeclaredMethod("getActiveEditor");
                m.setAccessible(true);
                Object inner = m.invoke(editor);
                if (inner instanceof ITextEditor ite) return ite;
                if (inner instanceof IEditorPart iep) {
                    adapted = iep.getAdapter(ITextEditor.class);
                    if (adapted != null) return adapted;
                }
            } catch (Exception ignored) { /* reflection failed — fall through */ }
        }

        return null;
    }

    /** A single error/warning marker from the SAP ABAP compiler. */
    public record EditorMarker(int line, int severity, String message) {
        /** IMarker.SEVERITY_ERROR = 2, WARNING = 1, INFO = 0 */
        public boolean isError()   { return severity == IMarker.SEVERITY_ERROR; }
        public boolean isWarning() { return severity == IMarker.SEVERITY_WARNING; }
        public String severityLabel() {
            return switch (severity) {
                case IMarker.SEVERITY_ERROR   -> "ERROR";
                case IMarker.SEVERITY_WARNING -> "WARNING";
                default                       -> "INFO";
            };
        }
    }

    /**
     * Reads the actual compiler error/warning markers from the active editor.
     * These are the real SAP ABAP syntax check results (the red/yellow icons
     * shown in the editor gutter). Must be called from the UI thread.
     *
     * @return list of markers, or empty list if none found or not available.
     */
    public List<EditorMarker> getEditorMarkers() {
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if (window == null || window.getActivePage() == null) return List.of();
        IEditorPart editor = window.getActivePage().getActiveEditor();
        if (editor == null) return List.of();

        List<EditorMarker> result = new ArrayList<>();
        try {
            // Strategy 1: IFileEditorInput — standard Eclipse resource markers
            IEditorInput input = editor.getEditorInput();
            IResource resource = null;
            if (input instanceof IFileEditorInput fileInput) {
                resource = fileInput.getFile();
            } else {
                // Strategy 2: adapter — works for ADT editors that wrap remote resources
                resource = input.getAdapter(IResource.class);
            }
            if (resource != null) {
                IMarker[] markers = resource.findMarkers(
                    IMarker.PROBLEM, true, IResource.DEPTH_ZERO);
                for (IMarker marker : markers) {
                    int line = marker.getAttribute(IMarker.LINE_NUMBER, -1);
                    int severity = marker.getAttribute(IMarker.SEVERITY, -1);
                    String msg = marker.getAttribute(IMarker.MESSAGE, "");
                    if (line > 0 && !msg.isEmpty()) {
                        result.add(new EditorMarker(line, severity, msg));
                    }
                }
            }

            // Strategy 3: ADT-specific annotation model — if no resource markers found,
            // try reading from the annotation model via the text editor
            if (result.isEmpty()) {
                ITextEditor te = findTextEditor(editor);
                if (te != null) {
                    var annotationModel = te.getDocumentProvider()
                        .getAnnotationModel(te.getEditorInput());
                    if (annotationModel != null) {
                        IDocument doc = te.getDocumentProvider()
                            .getDocument(te.getEditorInput());
                        var iter = annotationModel.getAnnotationIterator();
                        while (iter.hasNext()) {
                            var annotation = iter.next();
                            String type = annotation.getType();
                            // ADT-precise annotation types first; fall back to generic name matching
                            boolean isAbapError   = "com.sap.adt.oo.ui.annotations.AbapError".equals(type)
                                || (type != null && (type.contains("error") || type.contains("Error")));
                            boolean isAbapWarning = "com.sap.adt.oo.ui.annotations.AbapWarning".equals(type)
                                || (type != null && (type.contains("warning") || type.contains("Warning")));
                            if (isAbapError || isAbapWarning) {
                                var pos = annotationModel.getPosition(annotation);
                                int line = (pos != null && doc != null)
                                    ? doc.getLineOfOffset(pos.getOffset()) + 1 : -1;
                                String msg = annotation.getText();
                                int sev = isAbapError
                                    ? IMarker.SEVERITY_ERROR : IMarker.SEVERITY_WARNING;
                                if (line > 0 && msg != null && !msg.isEmpty()) {
                                    result.add(new EditorMarker(line, sev, msg));
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception ex) {
            // Could not read markers — return empty
        }
        return result;
    }

    /**
     * Replaces the current selection (or whole document if nothing selected)
     * in the active editor with {@code newContent}.
     *
     * <p><b>For AI-generated full-artifact replacements (modes 1-9 in chat
     * view), prefer {@link #applyToEditorReplaceAll(String)}.</b> The
     * selection-based path here causes the "paste-below-existing-code"
     * symptom when the user has any selection (even one word) at apply
     * time: the AI's full corrected source gets inserted at the selection
     * point, leaving the rest of the buggy file intact.
     *
     * @return true if successfully applied.
     */
    public boolean applyToEditor(String newContent) {
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if (window == null || window.getActivePage() == null) return false;
        IEditorPart editor = window.getActivePage().getActiveEditor();
        if (editor == null) return false;
        ITextEditor te = findTextEditor(editor);
        if (te == null) return false;
        try {
            IDocument doc = te.getDocumentProvider().getDocument(te.getEditorInput());
            if (doc == null) return false;
            ITextSelection sel = (ITextSelection) te.getSelectionProvider().getSelection();
            if (sel != null && sel.getLength() > 0) {
                doc.replace(sel.getOffset(), sel.getLength(), newContent);
            } else {
                doc.set(newContent);
            }
            return true;
        } catch (Exception ex) {
            // Bug #1 fix: log the actual cause. Without this, the caller's
            // dialog ("Make sure an ABAP file is open") shows up regardless
            // of whether the failure was a BadLocationException, a read-only
            // ADT buffer, an encoding error, or anything else. The user has
            // no way to diagnose. Logging here surfaces the real exception
            // class + message in the workspace .log so support can read it.
            LOG.log(Level.WARNING, "applyToEditor failed: "
                    + ex.getClass().getSimpleName() + ": " + ex.getMessage(), ex);
            return false;
        }
    }

    /**
     * Replaces the entire active editor's content with {@code newContent},
     * IGNORING any current selection. Use this from Apply-to-Editor flows
     * where the AI returned a full artifact and the editor's existing
     * content must be wholly replaced.
     *
     * <p>Unlike {@link #applyToEditor}, which honours the current selection,
     * this path uses {@code IDocument.set} unconditionally so the editor is
     * wholly replaced regardless of caret/selection state — the right
     * behaviour when the AI returns a complete replacement artifact.
     *
     * @return true if successfully applied.
     */
    public boolean applyToEditorReplaceAll(String newContent) {
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if (window == null || window.getActivePage() == null) return false;
        IEditorPart editor = window.getActivePage().getActiveEditor();
        if (editor == null) return false;
        ITextEditor te = findTextEditor(editor);
        if (te == null) return false;
        try {
            IDocument doc = te.getDocumentProvider().getDocument(te.getEditorInput());
            if (doc == null) return false;
            doc.set(newContent);
            return true;
        } catch (Exception ex) {
            LOG.log(Level.WARNING, "applyToEditorReplaceAll failed: "
                    + ex.getClass().getSimpleName() + ": " + ex.getMessage(), ex);
            return false;
        }
    }
}
